FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DEFFROW_DB_PATH=/data/deffrow.db \
    DEFFROW_HOST=0.0.0.0 \
    PORT=8787

WORKDIR /app
RUN groupadd --system --gid 10001 deffrow \
    && useradd --system --uid 10001 --gid deffrow --home-dir /app deffrow \
    && mkdir -p /data \
    && chown deffrow:deffrow /data

COPY --chown=deffrow:deffrow control_plane /app/control_plane

USER deffrow
EXPOSE 8787
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python -c "from urllib.request import urlopen; urlopen('http://127.0.0.1:8787/healthz', timeout=3).read()"

CMD ["python", "-m", "control_plane.server"]
