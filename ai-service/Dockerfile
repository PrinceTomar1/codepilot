FROM python:3.11-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# psycopg (binary) doesn't need libpq-dev, but keep build tools minimal/none.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

ENV PORT=8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=6 \
    CMD curl -f http://localhost:${PORT:-8000}/health || exit 1

# Shell form (not exec-form CMD array) so $PORT is actually substituted at container start --
# platforms like Railway assign a dynamic port via this env var, and a hardcoded --port 8000
# would silently ignore it.
CMD uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}
