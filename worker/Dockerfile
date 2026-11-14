# One image, two commands:
#   agent  (default):  python -m luna.agent start     LiveKit agent worker (health check on :8081)
#   web:               python -m luna.web             token endpoint + web UI (:8080)
#
# Provider plugins are build args so the image only contains what config.yaml uses:
#   docker build -f worker/Dockerfile --build-arg EXTRAS="openai deepgram cartesia" -t luna-worker .   (from the repo root)
FROM python:3.12-slim AS build
ARG EXTRAS="openai deepgram cartesia"
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
ARG UV_VERSION=0.13.5
RUN pip install --no-cache-dir "uv==${UV_VERSION}"
WORKDIR /app
COPY worker/pyproject.toml worker/uv.lock worker/README.md ./
# Word splitting of the generated --extra list is intended.
# hadolint ignore=SC2046
RUN uv sync --frozen --no-dev --no-install-project $(for e in $EXTRAS; do printf -- '--extra %s ' "$e"; done)
COPY worker/src ./src
# hadolint ignore=SC2046
RUN uv sync --frozen --no-dev $(for e in $EXTRAS; do printf -- '--extra %s ' "$e"; done)
# Fetch the local turn-detector / VAD weights now so the first conversation doesn't wait for a download.
COPY worker/config.yaml ./
COPY web ./web
RUN /app/.venv/bin/python -m livekit.agents download-files

FROM python:3.12-slim
# libgomp1: needed by onnxruntime (turn detector / VAD)
# Distro package versions drift; the base image tag pins the distro.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 luna
WORKDIR /app
COPY --from=build --chown=luna:luna /app /app
COPY --from=build --chown=luna:luna /root/.cache /home/luna/.cache
RUN mkdir -p /data && chown luna:luna /data
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1 \
    LUNA_CONFIG=/app/config.yaml LUNA_WEB_DIR=/app/web LUNA_STATE_DIR=/data/state LUNA_AUDIT_LOG=/data/logs/audit.jsonl
USER 10001
VOLUME /data
EXPOSE 8081 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8081', timeout=3)"]
CMD ["python", "-m", "luna.agent", "start"]
