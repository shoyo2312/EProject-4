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

# Nothing may reach out at runtime: with the cache already populated this turns a would-be
# network call into an immediate error instead of a hang inside a request.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

COPY verdict.py escalation.py app.py ./

EXPOSE 8099
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8099"]
