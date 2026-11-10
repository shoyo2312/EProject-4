"""Violence, gore, drugs, accidents and gambling in video frames: CLIP, zero-shot.

The NSFW model answers one question — is this explicit — and a fight, a bloody wound or a betting
app is none of that, so all of them scored about 0.02 there and were published. There is no
open-weight classifier trained on these five that runs on CPU, so this asks CLIP instead: each
frame is compared with a set of written descriptions, and the share of the match that lands on a
category's descriptions is that category's score.

The descriptions are the model. The benign ones matter as much as the harmful ones: a softmax over
"a car on fire" alone would give every frame 1.0 for it, and it is the "person driving a car" and
"fire in a fireplace" next to it that a frame of ordinary footage settles on instead. Adding,
removing or rewording any line moves every score, so it is a change of weights in all but name —
bump HARM_MODERATION_MODEL_VERSION and score a judged set again (score_samples.py) before it ships.

Things it cannot see, by construction: a drug deal that looks like a person holding money, a horror
clip that is only dark and red, anything that is only in the audio. A caption still goes through the
text rules, and the rest is what reports are for.

The weights are openai's own repository at a pinned commit, checked against their SHA-256 at
build. It ships only a pickle (pytorch_model.bin); transformers reads it with
``torch.load(weights_only=True)``, which builds tensors and refuses anything that would run code.
"""

import hashlib
import os

import torch
from transformers import CLIPModel, CLIPProcessor

# One entry per label this scorer reports. Labels travel to the video document and the admin
# console as-is, next to "nsfw".
CATEGORIES: dict[str, tuple[str, ...]] = {
    "violence": (
        "a photo of people fighting",
        "a photo of a person punching another person",
        "a photo of a violent assault",
        "a photo of a person threatening someone with a gun",
        "a photo of a person pointing a gun at someone",
        "a photo of a person attacking someone with a knife",
    ),
    "gore": (
        "a photo of a bloody wound",
        "a photo of a person covered in blood",
        "a photo of a severely injured person bleeding",
        "a photo of a dead body",
    ),
    "drugs": (
        "a photo of a person snorting cocaine",
        "a photo of lines of white powder drugs on a table",
        "a photo of a syringe injecting drugs",
        "a photo of illegal drug pills and powder",
        "a photo of a person smoking a crack pipe",
    ),
    "accident": (
        "a photo of a car crash",
        "a photo of a car on fire",
        "a photo of a traffic accident",
        "a photo of a burning wrecked vehicle",
    ),
    "gambling": (
        "a photo of a sports betting app on a phone screen",
        "a photo of online gambling odds",
        "a photo of a casino table with poker chips",
        "a photo of a slot machine",
    ),
}

# What an ordinary frame should land on instead. Cigarettes and vapes are here on purpose: they are
# legal, and without a place of their own they would drift into "drugs". So are the near misses of
# each category — driving next to the crash, holding money next to the drugs, a phone next to the
# betting app, a fireplace next to the burning car.
BENIGN: tuple[str, ...] = (
    "a photo of a person talking to the camera",
    "a photo of people dancing",
    "a photo of a person smoking a cigarette",
    "a photo of a person vaping an e-cigarette",
    "a photo of food",
    "a photo of a person cooking in a kitchen",
    "a landscape photo of nature",
    "an aerial photo of a city",
    "a photo of a city street",
    "an anime illustration",
    "a cartoon drawing",
    "a photo of a pet",
    "a photo of a person playing sports",
    "a photo of people at a party",
    "a photo of a concert with stage lights",
    "a photo of a person driving a car",
    "a photo of a person holding money",
    "a photo of a person using a smartphone",
    "a screenshot of a video game",
    "a dark photo",
    "a blurry photo",
    "a photo of a person sitting indoors",
    "a photo of text on a screen",
    "a photo of a person wearing makeup",
    "a photo of a fire in a fireplace",
    "a photo of a person sleeping",
    # Near misses found on real uploads (harm-v2). Without them a gym set and anime fight edits
    # scored "a person punching another person" up to 0.75, an anime peace sign "pointing a gun",
    # and a cat on a laptop and a cruise ship "online gambling odds".
    "a photo of a person lifting weights at the gym",
    "an anime character",
    "an anime action scene",
    "a photo of a laptop computer",
    "a photo of a ship on the sea",
)

# What the image needs from the repository: no flax_model.msgpack, which is the same weights again
# for another framework and another 600 MB.
FILES = [
    "pytorch_model.bin",
    "config.json",
    "preprocessor_config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "vocab.json",
    "merges.txt",
    "special_tokens_map.json",
]

WEIGHTS = "pytorch_model.bin"


def download(repo: str, revision: str, sha256: str) -> str:
    """Fetch the pinned files into the Hugging Face cache and check the weights. Build time only."""
    from huggingface_hub import snapshot_download

    path = snapshot_download(repo, revision=revision, allow_patterns=FILES)
    if sha256:
        actual = _sha256(os.path.join(path, WEIGHTS))
        if actual != sha256:
            raise RuntimeError(f"{repo}@{revision} {WEIGHTS} has SHA-256 {actual}, expected {sha256}")
    return path


def local_path(repo: str, revision: str) -> str:
    """Where download() put the files. Offline: a container never fetches at runtime."""
    from huggingface_hub import snapshot_download

    return snapshot_download(repo, revision=revision, allow_patterns=FILES, local_files_only=True)


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def prompts() -> tuple[list[str], list[str]]:
    """Every description in one list, and the category each one belongs to ("benign" for the rest)."""
    texts, owners = [], []
    for category, descriptions in CATEGORIES.items():
        texts += descriptions
        owners += [category] * len(descriptions)
    texts += BENIGN
    owners += ["benign"] * len(BENIGN)
    return texts, owners


def category_scores(probabilities: list[list[float]], owners: list[str]) -> dict[str, list[float]]:
    """Per category, per frame: the summed probability of that category's descriptions.

    Summed rather than the best single description, because a frame of a fight splits its match
    between "people fighting" and "a violent assault" — taking the larger half would score real
    violence lower the more ways there are to describe it.
    """
    scores = {category: [] for category in CATEGORIES}
    for row in probabilities:
        totals = dict.fromkeys(CATEGORIES, 0.0)
        for probability, owner in zip(row, owners):
            if owner in totals:
                totals[owner] += probability
        for category, total in totals.items():
            scores[category].append(total)
    return scores


class HarmScorer:
    """Category scores for a batch of frames, one forward pass of the image encoder per call."""

    def __init__(self, path: str):
        model = CLIPModel.from_pretrained(path)
        model.eval()
        processor = CLIPProcessor.from_pretrained(path)
        texts, self.owners = prompts()

        # The descriptions never change while the service runs, so they are encoded once here and
        # the text encoder is dropped: it is a third of the model's memory and nothing reads it again.
        with torch.inference_mode():
            embedded = model.get_text_features(**processor(text=texts, return_tensors="pt", padding=True))
        self.text = embedded / embedded.norm(dim=-1, keepdim=True)
        self.scale = model.logit_scale.exp().item()
        model.text_model = None

        self.model = model
        self.processor = processor

    def __call__(self, images) -> dict[str, list[float]]:
        with torch.inference_mode():
            pixels = self.processor(images=images, return_tensors="pt")
            embedded = self.model.get_image_features(**pixels)
            embedded = embedded / embedded.norm(dim=-1, keepdim=True)
            probabilities = (self.scale * embedded @ self.text.T).softmax(dim=-1).tolist()
        return category_scores(probabilities, self.owners)
