FROM python:3.13-slim
WORKDIR /app
COPY server.py .
ENV SCREEN_BIND=0.0.0.0
CMD ["python", "-u", "server.py"]
