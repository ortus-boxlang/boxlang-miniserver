# BoxLang MiniServer JSON Configuration

The BoxLang MiniServer supports loading configuration from a JSON file. This allows you to store all server settings in one place instead of passing them as command-line arguments every time.

## Usage

### Automatic Loading

If you run `boxlang-miniserver` with no arguments, it will automatically look for a `miniserver.json` file in the current directory:

```bash
boxlang-miniserver
```

### Explicit Path

You can also specify the path to a JSON configuration file:

```bash
boxlang-miniserver /path/to/config.json
```

### Override with CLI

Command-line arguments always override JSON configuration:

```bash
boxlang-miniserver miniserver.json --port 9090 --debug
```

## Configuration Options

All the following options are supported in the JSON configuration file:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `port` | number | 8080 | The port to listen on |
| `host` | string | "0.0.0.0" | The host to bind to |
| `webRoot` | string | current directory | Path to the webroot directory |
| `debug` | boolean | false | Enable debug mode |
| `configPath` | string | null | Path to BoxLang configuration file |
| `serverHome` | string | null | BoxLang server home directory |
| `rewrites` | boolean | false | Enable URL rewrites |
| `rewriteFileName` | string | "index.bxm" | Rewrite target file |
| `healthCheck` | boolean | false | Enable health check endpoints |
| `healthCheckSecure` | boolean | false | Restrict detailed health info to localhost only |
| `envFile` | string | null | Path to custom environment file (relative or absolute) |
| `warmupUrl` | string | null | Single URL to call after server starts (for application warmup) |
| `warmupUrls` | array | [] | Array of URLs to call after server starts (for application warmup) |

## Example Configuration Files

### Basic Configuration

```json
{
  "port": 8080,
  "webRoot": "./www"
}
```

### Development Configuration

```json
{
  "port": 8080,
  "host": "127.0.0.1",
  "webRoot": "./src/webapp",
  "debug": true,
  "rewrites": true,
  "rewriteFileName": "index.bxm"
}
```

### Production Configuration

```json
{
  "port": 80,
  "host": "0.0.0.0",
  "webRoot": "/var/www/myapp",
  "debug": false,
  "rewrites": true,
  "rewriteFileName": "index.bxm",
  "healthCheck": true,
  "healthCheckSecure": true,
  "serverHome": "/opt/boxlang",
  "envFile": "/etc/boxlang/.env.production"
}
```

### Complete Configuration

```json
{
  "port": 8080,
  "host": "0.0.0.0",
  "webRoot": "./www",
  "debug": true,
  "configPath": "/path/to/boxlang.json",
  "serverHome": "/opt/boxlang",
  "rewrites": true,
  "rewriteFileName": "index.bxm",
  "healthCheck": true,
  "healthCheckSecure": false,
  "envFile": ".env.production",
  "warmupUrl": "/index.bxm"
}
```

### Configuration with Warmup URLs

Single warmup URL:

```json
{
  "port": 8080,
  "webRoot": "./www",
  "warmupUrl": "/app/warmup"
}
```

Multiple warmup URLs:

```json
{
  "port": 8080,
  "webRoot": "./www",
  "warmupUrls": [
    "/app/init",
    "/health",
    "http://localhost:8080/cache/preload"
  ]
}
```

## Configuration Priority

Configuration values are loaded in the following order (later sources override earlier ones):

1. **Default values** - Built-in defaults
2. **Environment variables** - `BOXLANG_*` environment variables
3. **JSON configuration** - Values from the JSON file
4. **Command-line arguments** - Explicit CLI flags

For example, if you have:

- Environment variable: `BOXLANG_PORT=3000`
- JSON file: `"port": 8080`
- CLI argument: `--port 9090`

The server will start on port **9090** (CLI overrides all).

## Notes

- The JSON file must be valid JSON (no comments allowed in the actual file)
- All fields are optional - you only need to specify the ones you want to change
- Null values in the JSON file will be treated as "not set"
- Boolean values must be lowercase (`true` or `false`)
- String paths can be relative or absolute

### Environment File Loading

The `envFile` option allows you to specify a custom environment file to load instead of the default `.env` file in the webroot:

- If `envFile` is not specified, the server looks for `.env` in the webroot directory (default behavior)
- If `envFile` is specified, it loads that file instead
- The path can be relative (resolved from current directory) or absolute
- Environment variables are loaded as system properties and can be used throughout the application

Example:

```json
{
  "envFile": ".env.local"
}
```

or

```json
{
  "envFile": "/etc/myapp/.env.production"
}
```

### Warmup URLs

The `warmupUrl` and `warmupUrls` options allow you to specify URLs that should be called immediately after the server starts. This is useful for:

- Initializing application caches
- Pre-loading data
- Triggering startup routines
- Ensuring the application is fully ready before serving traffic

**Single URL:**

```json
{
  "warmupUrl": "/app/init"
}
```

**Multiple URLs (array):**

```json
{
  "warmupUrls": [
    "/app/cache/warm",
    "/app/db/init",
    "http://localhost:8080/health"
  ]
}
```

**Notes:**
- URLs can be relative (e.g., `/app/init`) or absolute (e.g., `http://localhost:8080/health`)
- Relative URLs are resolved against the server's base URL
- URLs are called in the order specified
- Each request has a 60-second timeout
- Success (2xx-3xx) and failure (4xx-5xx) responses are logged
- Failed warmup requests do not prevent the server from starting
- You can use both via CLI with `--warmup-url` (can be repeated) or in JSON config
