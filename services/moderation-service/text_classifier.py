"""A Vietnamese hate-speech classifier: visolex/phobert-v2-hsd, loaded without its remote code.

The checkpoint is PhoBERT-base-v2 fine-tuned on ViHSD (CLEAN / OFFENSIVE / HATE). Its repository
ships the model class as Python (models.py) and points its config at it, so the obvious way to load
it is ``trust_remote_code=True`` — which runs whatever that repository holds, at build and at every
start, with this service's privileges. The class is small: the RoBERTa encoder, its pooler output,
dropout and one linear layer. It is written out below instead, and the weights are read with
safetensors, which holds tensors and nothing that executes. ``strict=True`` makes a checkpoint of
any other shape fail at load — at image build — rather than score text with half its weights.

The commit is pinned and the weights are checked against their SHA-256 at build, so the bytes in
the image are the bytes that were reviewed, whatever happens to the repository afterwards.
"""

import hashlib
import os

import torch
from torch import nn
from transformers import AutoModel, PhobertTokenizer, RobertaConfig

from text_verdict import windows

LABELS = ("CLEAN", "OFFENSIVE", "HATE")

# phobert-base-v2's encoder. The checkpoint's own config.json names its base model but does not carry
# its shape, so these are copied from vinai/phobert-base-v2's config. A wrong value here is a load
# failure (strict), not a quiet mismatch — except layer_norm_eps, which is why it is spelled out.
ENCODER = {
    "vocab_size": 64001,
    "max_position_embeddings": 258,
    "hidden_size": 768,
    "num_hidden_layers": 12,
    "num_attention_heads": 12,
    "intermediate_size": 3072,
    "hidden_act": "gelu",
    "hidden_dropout_prob": 0.1,
    "attention_probs_dropout_prob": 0.1,
    "type_vocab_size": 1,
    "initializer_range": 0.02,
    "layer_norm_eps": 1e-05,
    "pad_token_id": 1,
    "bos_token_id": 0,
    "eos_token_id": 2,
    "position_embedding_type": "absolute",
}

# What the image needs from the repository, and nothing that runs: models.py and __init__.py stay
# behind, and so does training_args.bin, which is a pickle.
FILES = [
    "model.safetensors",
    "vocab.txt",
    "bpe.codes",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "added_tokens.json",
]

WEIGHTS = "model.safetensors"

# Tokens per piece of text and how far apart pieces start — see text_verdict.windows. The encoder
# could read 254 at once; a comment-sized piece is what it scores reliably.
WINDOW = 64
STRIDE = 48


class HateSpeechClassifier(nn.Module):
    """visolex's PhoBERTV2Model, rewritten: encoder, pooler output, dropout, linear head.

    The attribute names are the checkpoint's tensor prefixes (``encoder.*``, ``classifier.*``), which
    is what lets its state dict load into this class unchanged.
    """

    def __init__(self, config: RobertaConfig):
        super().__init__()
        self.encoder = AutoModel.from_config(config)
        self.dropout = nn.Dropout(0.1)
        self.classifier = nn.Linear(config.hidden_size, len(LABELS))

    def forward(self, input_ids, attention_mask):
        pooled = self.encoder(input_ids=input_ids, attention_mask=attention_mask).pooler_output
        return self.classifier(self.dropout(pooled))


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


class TextScorer:
    """Class probabilities for each window of each text, in one forward pass per call."""

    def __init__(self, path: str, max_windows: int):
        from safetensors.torch import load_file

        self.max_windows = max_windows
        self.tokenizer = PhobertTokenizer.from_pretrained(path)
        model = HateSpeechClassifier(RobertaConfig(**ENCODER))
        model.load_state_dict(load_file(os.path.join(path, WEIGHTS)), strict=True)
        model.eval()
        self.model = model

    def __call__(self, texts: list[str]) -> list[list[dict]]:
        """For each text, one probability dict per window — the decision rule picks among them."""
        cls, sep, pad = self.tokenizer.cls_token_id, self.tokenizer.sep_token_id, self.tokenizer.pad_token_id

        sequences: list[list[int]] = []
        owners: list[int] = []
        for index, text in enumerate(texts):
            ids = self.tokenizer(text, add_special_tokens=False)["input_ids"]
            for piece in windows(ids, WINDOW, STRIDE, self.max_windows):
                sequences.append([cls, *piece, sep])
                owners.append(index)

        # Padded to the longest sequence in this call, not to 256: a comment is a few dozen tokens,
        # and padding it out to the encoder's limit would make every comment cost a full window.
        longest = max(len(sequence) for sequence in sequences)
        input_ids = torch.full((len(sequences), longest), pad, dtype=torch.long)
        attention_mask = torch.zeros((len(sequences), longest), dtype=torch.long)
        for row, sequence in enumerate(sequences):
            input_ids[row, :len(sequence)] = torch.tensor(sequence, dtype=torch.long)
            attention_mask[row, :len(sequence)] = 1

        with torch.inference_mode():
            probabilities = torch.softmax(self.model(input_ids, attention_mask), dim=-1).tolist()

        per_text: list[list[dict]] = [[] for _ in texts]
        for owner, row in zip(owners, probabilities):
            per_text[owner].append(dict(zip(LABELS, row)))
        return per_text
