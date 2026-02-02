# BoxLang MiniServer - AI Coding Assistant Instructions

## Project Overview

BoxLang MiniServer is a lightweight, high-performance web server for the BoxLang JVM language, built on JBoss Undertow. It's designed for serverless apps, microservices, and APIs—a minimal alternative to the full CommandBox server.

**Tech Stack**: Java 21+, Gradle, Undertow 2.3.x, BoxLang Runtime, WebSocket support

## Architecture

### Core Components

- **MiniServer.java** - Main entry point (`ortus.boxlang.web.MiniServer`). Builds Undertow server, manages handler chain, processes CLI args and JSON config
- **Handler Pipeline** (request flows through in order):
  1. `WebsocketHandler` - Intercepts WebSocket connections at `/ws`
  2. `FrameworkRewritesBuilder` - Optional URL rewrites to specified file (e.g., `index.bxm`)
  3. `EncodingHandler` - Gzip compression for responses >1500 bytes
  4. `HealthCheckHandler` - Optional health endpoints at `/health`, `/health/ready`, `/health/live`
  5. `SecurityHandler` - Blocks access to hidden files (starting with `.`)
  6. `WelcomeFileHandler` - Routes to welcome files (index.bxm, index.cfm, etc.)
  7. `BLHandler` - Executes BoxLang files (*.bx[ms], *.cf[cms])
  8. `ResourceHandler` - Serves static files

### Key Patterns

**BoxLang Integration**: Uses `BoxHTTPUndertowExchange` to bridge Undertow's `HttpServerExchange` to BoxLang's web runtime. Calls `WebRequestExecutor.execute()` with FR transaction tracking.

**Configuration Cascade** (priority order):
1. CLI arguments (highest)
2. `miniserver.json` (auto-loaded from CWD or explicit path)
3. Environment variables (`BOXLANG_PORT`, `BOXLANG_WEBROOT`, etc.)
4. Defaults (port 8080, host 0.0.0.0)

**Dependency Management**: Uses local BoxLang JARs for development (`../boxlang/build/libs/`) or downloads from `src/test/resources/libs/` if unavailable. This dual-mode supports both module development and standalone builds.

## Development Workflows

### Building & Running

```bash
# Download BoxLang dependencies (first time or when BoxLang updates)
./gradlew downloadBoxLang

# Build distribution (creates ZIP in build/distributions/)
./gradlew build

# Run directly via Gradle
./gradlew run

# Run the built JAR
java -jar build/distributions/boxlang-miniserver-1.10.0-snapshot.jar --port 8080 --webroot ./src/test/www
```

### Testing

- **Framework**: JUnit 5 (Jupiter) + Mockito + Truth assertions
- **Location**: `src/test/java/`
- **Run**: `./gradlew test` (outputs to stdout via `showStandardStreams = true`)

**⚠️ CRITICAL REQUIREMENT**: Every new feature MUST include corresponding unit tests. Pull requests without tests will not be merged.

**Testing Strategy**:
1. **Unit Tests**: Test individual handlers, predicates, and utilities in isolation
2. **Integration Tests**: Test handler chains and full request/response cycles
3. **Mock External Dependencies**: Use Mockito to mock BoxLang runtime and HttpServerExchange
4. **Truth Assertions**: Prefer Truth's fluent assertions over plain JUnit asserts for readability

**Test Naming Convention**:
- Test classes: `{ClassName}Test.java` (e.g., `SecurityHandlerTest.java`)
- Test methods: `test{Behavior}_{Scenario}_{ExpectedResult}` (e.g., `testSecurityHandler_hiddenFile_returnsNotFound`)

**Example Test Structure**:
```java
@Test
void testBLHandler_validBoxLangFile_executesSuccessfully() throws Exception {
    // Arrange
    HttpServerExchange mockExchange = mock(HttpServerExchange.class);
    when(mockExchange.getRequestURI()).thenReturn("/test.bxm");

    // Act
    handler.handleRequest(mockExchange);

    // Assert
    assertThat(mockExchange.getStatusCode()).isEqualTo(200);
}
```

### Code Formatting

Uses **Spotless** with Ortus style (`.ortus-java-style.xml`):
- **Check**: `./gradlew spotlessCheck`
- **Auto-fix**: `./gradlew spotlessApply`
- **Scope**: Excludes build outputs, examples
- Always run before commits to avoid CI failures

### Version Bumping

```bash
./gradlew bumpPatchVersion  # 1.10.0 → 1.10.1
./gradlew bumpMinorVersion  # 1.10.0 → 1.11.0
./gradlew bumpMajorVersion  # 1.10.0 → 2.0.0
```

Modifies `gradle.properties` for both `version` and `boxlangVersion`.

## Project Conventions

### File Extensions

- `*.bxm` - BoxLang markup files (HTML + BoxLang)
- `*.bxs` - BoxLang script files
- `*.cfm/.cfc/.cfs` - Legacy CFML compatibility

### Environment Variables

- **BOXLANG_PORT** - Server port (default: 8080)
- **BOXLANG_WEBROOT** - Web root path
- **BOXLANG_HOST** - Bind address (default: 0.0.0.0)
- **BOXLANG_REWRITES** - Enable URL rewrites (true/false)
- **BOXLANG_HEALTH_CHECK** - Enable health endpoints (true/false)
- See `MiniServer.ServerConfig` for full list

### Configuration Files

- **miniserver.json** - Server configuration (port, webRoot, debug, rewrites, healthCheck, etc.). See `MINISERVER_JSON.md` for schema.
- **.env** - Auto-loaded from webroot or custom path via `envFile` config. Sets system properties.
- **gradle.properties** - Build version and BoxLang dependency version

### Build Artifacts

- **build/distributions/** - Production artifacts (JAR + ZIP)
- **build/evergreen/** - "Latest" and "snapshot" aliases for CI/CD
- **Checksums**: Auto-generated SHA-256 and MD5 for all artifacts

## Important Constraints

1. **BoxLang Dependency**: Must match core runtime version. Check `gradle.properties` for `boxlangVersion`.
2. **Java Version**: JDK 21+ required (set in `gradle.properties` via `jdkVersion`).
3. **Undertow Threading**: Dispatch off IO thread before blocking operations (see `BLHandler.handleRequest()`).
4. **Path Separators**: Always use forward slashes (`/`) in code—normalized across platforms.
5. **PR Branches**: Never submit PRs to `master`. Target `development` branch (see `CONTRIBUTING.md`).

## Integration Points

- **BoxLang Runtime** (`BoxRunner`, `BoxRuntime`): Entry to BoxLang execution engine
- **Web Support** (`WebRequestExecutor`, `BoxHTTPUndertowExchange`): HTTP request adapters
- **Undertow Predicates**: Custom predicates in `ortus.boxlang.web.predicates` for routing logic
- **WebSocket**: JSR-356 implementation via `WebsocketHandler` and `WebsocketReceiveListener`

## Security & WebSocket Details

### Security Handler Implementation

The `SecurityHandler` provides baseline security by blocking access to sensitive files:

- **Hidden Files**: Blocks any request to files/directories starting with `.` (e.g., `.env`, `.git`)
- **Returns**: HTTP 404 for blocked requests to avoid revealing file existence
- **Pattern**: Regex check on request path before passing to next handler
- **Placement**: Must be early in handler chain (before file serving)

**Example Protected Files**:
- `.env`, `.git/`, `.svn/`, `.DS_Store`
- Configuration files: `.boxlang.json`, `.editorconfig`

### WebSocket Support

**Architecture**:
- **Endpoint**: All WebSocket connections at `/ws` path
- **Standard**: JSR-356 (Java API for WebSocket)
- **Handler**: `WebsocketHandler` intercepts at top of handler chain
- **Listener**: `WebsocketReceiveListener` processes incoming messages
- **Integration**: Uses ThreadLocal `currentExchange` for HTTP context access

**WebSocket Flow**:
1. Client connects to `ws://host:port/ws`
2. `WebsocketHandler` intercepts and upgrades connection
3. `WebsocketReceiveListener` handles binary/text messages
4. BoxLang code can access WebSocket via server APIs

**Usage Pattern**:
```java
// WebSocket handler checks path and upgrades if matched
if (exchange.getRequestPath().equals(WEBSOCKET_PATH)) {
    Handlers.websocket((exchange, channel) -> {
        channel.getReceiveSetter().set(new WebsocketReceiveListener());
        channel.resumeReceives();
    }).handleRequest(exchange);
    return;
}
```

## Deployment Scenarios

BoxLang MiniServer supports multiple deployment modes:

### 1. Standalone Binary (JAR)

**Production Use**:
```bash
# Download latest release
wget https://downloads.ortussolutions.com/ortussolutions/boxlang-runtimes/boxlang-miniserver/1.10.0/boxlang-miniserver-1.10.0.zip

# Extract and run
unzip boxlang-miniserver-1.10.0.zip
cd boxlang-miniserver-1.10.0/bin
./boxlang-miniserver --webroot /var/www/myapp --port 8080
```

**With miniserver.json**:
```bash
# Place miniserver.json in webroot or current directory
./boxlang-miniserver
```

**Systemd Service** (`/etc/systemd/system/boxlang-miniserver.service`):
```ini
[Unit]
Description=BoxLang MiniServer
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/var/www/myapp
ExecStart=/opt/boxlang-miniserver/bin/boxlang-miniserver /var/www/myapp/miniserver.json
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### 2. Docker Deployment

**Dockerfile** (multi-stage build):
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY . .
RUN ./gradlew clean build -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/build/distributions/boxlang-miniserver-*.jar /app/miniserver.jar
COPY --from=builder /build/src/test/www /app/www

EXPOSE 8080
ENV BOXLANG_PORT=8080
ENV BOXLANG_WEBROOT=/app/www

ENTRYPOINT ["java", "-jar", "/app/miniserver.jar"]
```

**Docker Compose** (`docker-compose.yml`):
```yaml
version: '3.8'
services:
  miniserver:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - ./www:/app/www
      - ./miniserver.json:/app/miniserver.json
    environment:
      - BOXLANG_DEBUG=false
      - BOXLANG_HEALTH_CHECK=true
    restart: unless-stopped
```

**Run with Docker**:
```bash
# Build image
docker build -t boxlang-miniserver .

# Run container
docker run -d -p 8080:8080 \
  -v $(pwd)/www:/app/www \
  -e BOXLANG_PORT=8080 \
  boxlang-miniserver

# With miniserver.json
docker run -d -p 8080:8080 \
  -v $(pwd)/www:/app/www \
  -v $(pwd)/miniserver.json:/app/miniserver.json \
  boxlang-miniserver /app/miniserver.json
```

### 3. Cloud Deployments

**AWS Lambda** (via custom runtime):
- Package JAR with Lambda runtime wrapper
- Use API Gateway for HTTP routing
- Configure memory >= 512MB for optimal performance

**Azure Container Instances**:
```bash
az container create \
  --resource-group myResourceGroup \
  --name boxlang-miniserver \
  --image boxlang-miniserver:latest \
  --dns-name-label boxlang-app \
  --ports 8080
```

**Google Cloud Run**:
```bash
gcloud run deploy boxlang-miniserver \
  --image gcr.io/PROJECT_ID/boxlang-miniserver \
  --platform managed \
  --port 8080 \
  --allow-unauthenticated
```

### 4. Kubernetes Deployment

**Deployment Manifest** (`k8s-deployment.yaml`):
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: boxlang-miniserver
spec:
  replicas: 3
  selector:
    matchLabels:
      app: boxlang-miniserver
  template:
    metadata:
      labels:
        app: boxlang-miniserver
    spec:
      containers:
      - name: miniserver
        image: boxlang-miniserver:latest
        ports:
        - containerPort: 8080
        env:
        - name: BOXLANG_PORT
          value: "8080"
        - name: BOXLANG_HEALTH_CHECK
          value: "true"
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: boxlang-miniserver
spec:
  selector:
    app: boxlang-miniserver
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```

## Common Tasks

### Adding a New Handler

1. Extend `HttpHandler` in `ortus.boxlang.web.handlers`
2. Insert into chain in `MiniServer.createHandlerChain()`
3. Order matters—security/auth should come before routing
4. **Write unit tests** for the handler before merging (see Testing section)

**Handler Template**:
```java
package ortus.boxlang.web.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public class MyCustomHandler implements HttpHandler {

    private HttpHandler next;

    public MyCustomHandler(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // Your logic here
        if (shouldHandle(exchange)) {
            // Handle request
            exchange.getResponseSender().send("Handled");
        } else {
            // Pass to next handler
            next.handleRequest(exchange);
        }
    }
}
```

### Modifying Configuration

1. Update `ServerConfig` class with new field
2. Add parsing in `parseConfiguration()` (env vars, CLI args)
3. Add JSON loading in `loadJsonConfiguration()`
4. Update `MINISERVER_JSON.md` documentation

### Debugging

Use `--debug` or `"debug": true` in JSON config to enable BoxLang debug mode. Health check endpoints (`--health-check`) provide runtime introspection.

## References

- **BoxLang Docs**: https://boxlang.ortusbooks.com/
- **Undertow Docs**: https://undertow.io/undertow-docs/
- **Project Repo**: https://github.com/ortus-boxlang/boxlang-miniserver
- **Issue Tracker**: https://ortussolutions.atlassian.net/browse/BL (Jira) or GitHub Issues
