FROM python:3.12-slim

WORKDIR /app

# The CPU wheel index, not the default one: the default torch wheel bundles the whole CUDA
# runtime and is several gigabytes for hardware this deployment does not have.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.5.1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the weights into the image. Downloading them at startup would mean a container that comes
# up without internet is a container that scores nothing, and every video it was sent goes to the
# admin queue — the fail-safe firing for a reason that has nothing to do with the videos.
ARG MODERATION_MODEL=Falconsai/nsfw_image_detection
RUN python -c "from transformers import pipeline; pipeline('image-classification', model='${MODERATION_MODEL}')"

# The text classifier, the same way. A commit rather than a branch, the weights checked against
# their SHA-256, and only the files that hold no code (see text_classifier.FILES). The test load is
# the point of the last line: a checkpoint whose shape is not what text_classifier.py expects fails
# here, at build, instead of on the first comment. An empty TEXT_MODERATION_MODEL builds an image
# without it, and /moderate/text then answers 503 — the callers fall back to their word rules.
ARG TEXT_MODERATION_MODEL=visolex/phobert-v2-hsd
ARG TEXT_MODERATION_MODEL_REVISION=221aca47de6568d519eba61a94d7fdae3ca680ae
ARG TEXT_MODERATION_MODEL_SHA256=72c9e731290e13995f143370b82d9076b2c6f6d41b0c099abc32d91aa0e10bf7
COPY text_verdict.py text_classifier.py ./
RUN if [ -n "$TEXT_MODERATION_MODEL" ]; then python -c "import os, text_classifier as t; \
t.TextScorer(t.download(os.environ['TEXT_MODERATION_MODEL'], os.environ['TEXT_MODERATION_MODEL_REVISION'], \
os.environ['TEXT_MODERATION_MODEL_SHA256']), 1)"; fi

# The zero-shot scorer for violence, gore, drugs, accidents and gambling, the same way: a commit,
# the weights checked against their SHA-256, only the files harm_classifier.FILES names, and a
# test load at build — which also encodes every description, so a typo that breaks the tokenizer
# fails here. An empty HARM_MODERATION_MODEL builds an image without it, and the frame check is
# then NSFW alone, as before.
ARG HARM_MODERATION_MODEL=openai/clip-vit-base-patch16
ARG HARM_MODERATION_MODEL_REVISION=57c216476eefef5ab752ec549e440a49ae4ae5f3
ARG HARM_MODERATION_MODEL_SHA256=ec89c7b09c749a60aae3c9cd910516f24b58214a7df060b48962d14c469cfbf0
COPY harm_classifier.py ./
RUN if [ -n "$HARM_MODERATION_MODEL" ]; then python -c "import os, harm_classifier as h; \
h.HarmScorer(h.download(os.environ['HARM_MODERATION_MODEL'], os.environ['HARM_MODERATION_MODEL_REVISION'], \
os.environ['HARM_MODERATION_MODEL_SHA256']))"; fi

# Nothing may reach out at runtime: with the cache already populated this turns a would-be
# network call into an immediate error instead of a hang inside a request. The text and harm
# models' names and commits are carried into the runtime defaults, so the service reads what the
# build fetched.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    TEXT_MODERATION_MODEL=${TEXT_MODERATION_MODEL} \
    TEXT_MODERATION_MODEL_REVISION=${TEXT_MODERATION_MODEL_REVISION} \
    HARM_MODERATION_MODEL=${HARM_MODERATION_MODEL} \
    HARM_MODERATION_MODEL_REVISION=${HARM_MODERATION_MODEL_REVISION}

COPY verdict.py escalation.py app.py ./

EXPOSE 8099
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8099"]
