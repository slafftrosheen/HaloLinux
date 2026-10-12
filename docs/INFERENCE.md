# Inference Configuration

## Supported Providers

HaloLinux uses OpenAI-compatible HTTP APIs for inference. This enables compatibility with:

| Provider | Type | Local/Remote | Auth |
|----------|------|--------------|------|
| Ollama | Local server | Local | None |
| llama.cpp server | Local server | Local | None |
| vLLM | Local server | Local | Optional |
| OpenAI | Cloud API | Remote | API Key |
| Groq | Cloud API | Remote | API Key |
| Cerebras | Cloud API | Remote | API Key |
| xAI | Cloud API | Remote | API Key |
| Custom | Any OpenAI-compatible | Local/Remote | Configurable |

## Configuration

```yaml
inference:
  provider: "ollama-local"        # Provider ID
  endpoint: "http://localhost:11434/v1"  # Base URL
  model: "llama3.1:8b"            # Model identifier
  api_key: ""                     # Optional: for authenticated endpoints
  timeout_seconds: 120            # Request timeout
  streaming: true                 # Enable streaming responses
```

## Provider Presets

### Ollama (Local)
```yaml
inference:
  provider: "ollama-local"
  endpoint: "http://localhost:11434/v1"
  model: "llama3.1:8b"
```

### llama.cpp Server (Local)
```yaml
inference:
  provider: "llamacpp-local"
  endpoint: "http://localhost:8080/v1"
  model: "llama3.1-8b-q4_k_m"
```

### OpenAI (Remote)
```yaml
inference:
  provider: "openai"
  endpoint: "https://api.openai.com/v1"
  model: "gpt-4o-mini"
  api_key: "sk-..."
```

### Groq (Remote, Fast)
```yaml
inference:
  provider: "groq"
  endpoint: "https://api.groq.com/openai/v1"
  model: "llama-3.1-70b-versatile"
  api_key: "gsk_..."
```

### Custom OpenAI-Compatible
```yaml
inference:
  provider: "my-custom-llm"
  endpoint: "http://192.168.1.100:8000/v1"
  model: "my-model"
  api_key: "optional-key"
```

## Model Discovery

Providers supporting model listing (Ollama, vLLM, llama.cpp with `--model-list`):
- Models fetched automatically on startup
- Use `halo-node config show` to see discovered models
- Select model in config or via CLI

## Streaming vs Non-Streaming

```yaml
inference:
  streaming: true   # Default: token-by-token streaming
  # streaming: false  # Wait for complete response
```

## Timeouts

```yaml
inference:
  connect_timeout_seconds: 10   # Connection establishment
  read_timeout_seconds: 120     # Response reading (streaming)
```

## Cancellation

Long-running requests can be cancelled via:
- SIGINT (Ctrl+C) during CLI `ask` command
- Management API cancellation endpoint
- Automatic timeout enforcement

## Troubleshooting

### Connection Refused
```bash
# Check if provider is running
curl http://localhost:11434/api/tags  # Ollama
curl http://localhost:8080/v1/models  # llama.cpp
```

### Authentication Failed
```bash
# Verify API key
curl -H "Authorization: Bearer $API_KEY" https://api.openai.com/v1/models
```

### Model Not Found
```bash
# List available models
curl http://localhost:11434/api/tags | jq '.models[].name'

# Pull missing model
ollama pull llama3.1:8b
```

### Timeout Errors
Increase timeouts in config for slow models:
```yaml
inference:
  read_timeout_seconds: 300  # 5 minutes for large models
```

## Local-First Priority

HaloLinux prioritizes local inference:
1. No API keys required for local providers
2. No data leaves the device
3. Works completely offline
4. Lower latency for interactive use

Remote providers are explicitly configured and opt-in.