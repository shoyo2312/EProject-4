FROM python:3.12-slim

# LightGBM needs libgomp at runtime; the wheel does not bundle it.
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY features.py app.py train.py ./

EXPOSE 8098
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8098"]
