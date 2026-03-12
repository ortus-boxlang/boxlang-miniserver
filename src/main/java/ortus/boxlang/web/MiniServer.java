/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.xnio.Option;
import org.xnio.Options;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import ortus.boxlang.runtime.BoxRunner;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.dynamic.casters.BooleanCaster;
import ortus.boxlang.runtime.dynamic.casters.IntegerCaster;
import ortus.boxlang.runtime.dynamic.casters.StringCaster;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.util.EncryptionUtil;
import ortus.boxlang.web.config.MiniServerConfig;
import ortus.boxlang.web.handlers.BLHandler;
import ortus.boxlang.web.handlers.FrameworkRewritesBuilder;
import ortus.boxlang.web.handlers.HealthCheckHandler;
import ortus.boxlang.web.handlers.SecurityHandler;
import ortus.boxlang.web.handlers.WebsocketHandler;
import ortus.boxlang.web.handlers.WelcomeFileHandler;

/**
 * The BoxLang MiniServer is a simple web server that serves BoxLang files and static files.
 *
 * The following command line arguments are supported:
 *
 * --help, -h - Show help information and exit.
 * --port, -p <port> - The port to listen on. Default is 8080.
 * --webroot, -w <path> - The path to the webroot. Default is {@code BOXLANG_HOME/www}
 * --debug, -d - Enable debug mode or not. Default is false.
 * --host <host> - The host to listen on. Default is {@code 0.0.0.0}.
 * --configPath, -c <path> - Path to BoxLang configuration file.
 * --serverHome, -s <path> - BoxLang server home directory.
 * --rewrites, -r [file] - Enable URL rewrites with optional rewrite file name.
 *
 * Examples:
 *
 * <pre>
 * java -jar boxlang-miniserver.jar --webroot /path/to/webroot --debug
 * java -jar boxlang-miniserver.jar --port 80 --webroot /var/www
 * java -jar boxlang-miniserver.jar --help
 * </pre>
 *
 * This will start the BoxLang MiniServer on port 8080, serving files from {@code /path/to/webroot}, and enable debug mode.
 */
public class MiniServer {

	/**
	 * Server-level constants (not part of configuration)
	 */
	private static final int								GZIP_MIN_SIZE			= 1500;
	private static final int								GZIP_PRIORITY			= 50;
	private static final String								WEBSOCKET_PATH			= "/ws";
	private static final List<String>						DEFAULT_WELCOME_FILES	= List.of(
	    "index.bxm", "index.bxs", "index.cfm", "index.cfs", "index.htm", "index.html"
	);

	/**
	 * BoxLang file pattern for request routing
	 */
	private static final String								BOXLANG_FILE_PATTERN	= "regex( '^(/.+?\\.cfml|/.+?\\.cf[cms]|.+?\\.bx[ms]{0,1})(/.*)?$' )";

	/**
	 * Flag to indicate if the server is shutting down.
	 */
	public static boolean									shuttingDown			= false;

	/**
	 * The Websocket handler for the server.
	 */
	public static WebsocketHandler							websocketHandler;

	/**
	 * The resource manager for the server. Placed here for easy access from predicates
	 */
	public static ResourceManager							resourceManager;

	/**
	 * ThreadLocal to store the current HttpServerExchange.
	 */
	private static final ThreadLocal<HttpServerExchange>	currentExchange			= new ThreadLocal<>();

	/**
	 * Main method to start the BoxLang MiniServer.
	 *
	 * @param args Command line arguments. See Help for details.
	 */
	public static void main( String[] args ) {
		// Check for informational flags before attempting configuration parsing
		for ( String arg : args ) {
			if ( arg.equalsIgnoreCase( "--help" ) || arg.equalsIgnoreCase( "-h" ) ) {
				printHelp();
				System.exit( 0 );
			}
			if ( arg.equalsIgnoreCase( "--version" ) || arg.equalsIgnoreCase( "-v" ) ) {
				printVersion();
				System.exit( 0 );
			}
		}

		try {
			// Parse configuration from environment, JSON file, and command line
			MiniServerConfig config = MiniServerConfig.fromArgs( args );

			// Normalize and validate the webroot path
			Path absWebRoot = normalizeWebroot( config.webRoot );

			// Convention: check for .boxlang.json in the webroot directory, mirroring
			// how .env is auto-discovered. CWD was already checked in fromArgs(); this
			// catches the case where --webroot points somewhere other than CWD.
			loadBoxLangConfig( absWebRoot, config );

			// Load up any .env files if they exist
			loadEnvFiles( absWebRoot, config );

			// Start the server
			startServer( config, absWebRoot );
		} catch ( IllegalArgumentException e ) {
			System.err.println( "IllegalArgumentException: " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
		} catch ( Exception e ) {
			System.err.println( "Failed to start server: " + e.getMessage() );
			e.printStackTrace();
			System.exit( 1 );
		}
	}

	/**
	 * Loads BoxLang configuration from a .boxlang.json file in the web root if configPath is not already set.
	 *
	 * @param absWebRoot The absolute path to the web root directory
	 * @param config     The server configuration to update with the detected config path
	 */
	private static void loadBoxLangConfig( Path absWebRoot, MiniServerConfig config ) {
		if ( config.configPath == null ) {
			Path webrootBoxLangConfig = absWebRoot.resolve( ".boxlang.json" );
			if ( java.nio.file.Files.exists( webrootBoxLangConfig ) ) {
				config.configPath = webrootBoxLangConfig.toAbsolutePath().toString();
				System.out.println( "+ Detected BoxLang config via convention (webroot): " + config.configPath );
			}
		}
	}

	/**
	 * Loads environment variables from a .env file in the web root directory or from a custom env file.
	 *
	 * @param absWebRoot The absolute path to the web root directory
	 * @param config     The server configuration (may contain custom envFile path)
	 */
	private static void loadEnvFiles( Path absWebRoot, MiniServerConfig config ) {
		Path envFile = null;

		// Check if a custom env file is specified in the configuration
		if ( config.envFile != null && !config.envFile.trim().isEmpty() ) {
			// Use custom env file path (can be relative or absolute)
			envFile = Paths.get( config.envFile );
			// If relative, resolve against current directory
			if ( !envFile.isAbsolute() ) {
				envFile = Paths.get( System.getProperty( "user.dir" ) ).resolve( config.envFile );
			}
			envFile = envFile.toAbsolutePath().normalize();
			// Warn if custom env file specified but doesn't exist
			if ( !envFile.toFile().exists() ) {
				System.err.println( "Warning: Custom environment file not found: " + envFile );
			}
		} else {
			// Default behavior: look for .env in web root
			envFile = absWebRoot.resolve( ".env" );
		}

		if ( envFile.toFile().exists() ) {
			Properties properties = new Properties();
			try {
				properties.load( java.nio.file.Files.newBufferedReader( envFile ) );
				System.out.println( "+ Loaded environment variables from: " + envFile );
				// Set system properties from the loaded properties
				for ( String key : properties.stringPropertyNames() ) {
					System.setProperty( key, properties.getProperty( key ) );
				}
			} catch ( Exception e ) {
				System.err.println( "Failed to load .env file: " + e.getMessage() );
			}
		}
	}

	// parseConfiguration() and loadJsonConfiguration() have been moved to MiniServerConfig

	/**
	 * Starts the server with the given configuration.
	 *
	 * @param config     The server configuration
	 * @param absWebRoot The absolute web root path
	 */
	private static void startServer( MiniServerConfig config, Path absWebRoot ) {
		var sTime = System.currentTimeMillis();

		// Output server information
		System.out.println( "+ Starting BoxLang Server..." );
		System.out.println( "  - Web Root: " + absWebRoot.toString() );
		System.out.println( "  - Host: " + config.host );
		System.out.println( "  - Port: " + config.port );
		System.out.println( "  - Debug: " + config.debug );
		System.out.println( "  - Config Path: " + config.configPath );
		System.out.println( "  - Server Home: " + config.serverHome );
		System.out.println( "  - Health Check: " + config.healthCheck );
		System.out.println( "  - Health Check Secure: " + config.healthCheckSecure );
		System.out.println( "+ Starting BoxLang Runtime..." );

		// Startup the runtime
		BoxRuntime	runtime		= BoxRuntime.getInstance( config.debug, config.configPath, config.serverHome ).waitForStart();
		IStruct		versionInfo	= runtime.getVersionInfo();
		System.out.println(
		    "  - BoxLang Version: " + versionInfo.getAsString( Key.of( "version" ) ) + " (Built On: "
		        + versionInfo.getAsString( Key.of( "buildDate" ) )
		        + ")" );
		System.out.println( "  - Runtime Started in " + ( System.currentTimeMillis() - sTime ) + "ms" );
		System.out.println( "  - Logs Directory: " + runtime.getLoggingService().getLogsDirectory() );

		// Build the web server
		Undertow BLServer = buildWebServer( absWebRoot, config );

		// Add shutdown hook for graceful shutdown
		addShutdownHook( BLServer, runtime );

		// Start the server
		BLServer.start();
		long	totalStartTime	= System.currentTimeMillis() - sTime;
		String	serverUrl		= "http://" + config.host.replace( "0.0.0.0", "localhost" ) + ":" + config.port;
		System.out.println( "+ BoxLang MiniServer started in " + totalStartTime + "ms at: " + serverUrl );

		// Execute warmup URLs if configured
		if ( !config.warmupUrls.isEmpty() ) {
			executeWarmupUrls( config.warmupUrls, serverUrl );
		}

		System.out.println( "Press Ctrl+C to stop the server." );
	}

	/**
	 * Executes warmup URLs to initialize the application.
	 *
	 * @param warmupUrls The list of URLs to call
	 * @param serverUrl  The base server URL
	 */
	private static void executeWarmupUrls( List<String> warmupUrls, String serverUrl ) {
		System.out.println( "+ Executing warmup URLs..." );
		HttpClient client = HttpClient.newBuilder()
		    .connectTimeout( Duration.ofSeconds( 30 ) )
		    .build();

		for ( String url : warmupUrls ) {
			try {
				// Resolve relative URLs against the server URL
				String fullUrl = url.startsWith( "http" ) ? url : serverUrl + ( url.startsWith( "/" ) ? url : "/" + url );

				System.out.println( "  - Calling: " + fullUrl );
				long					startTime	= System.currentTimeMillis();

				HttpRequest				request		= HttpRequest.newBuilder()
				    .uri( URI.create( fullUrl ) )
				    .timeout( Duration.ofSeconds( 60 ) )
				    .GET()
				    .build();

				HttpResponse<String>	response	= client.send( request, HttpResponse.BodyHandlers.ofString() );
				long					duration	= System.currentTimeMillis() - startTime;

				if ( response.statusCode() >= 200 && response.statusCode() < 400 ) {
					System.out.println( "    ✓ Success (" + response.statusCode() + ") in " + duration + "ms" );
				} else {
					System.err.println( "    ✗ Failed (" + response.statusCode() + ") in " + duration + "ms" );
				}
			} catch ( Exception e ) {
				System.err.println( "    ✗ Error calling " + url + ": " + e.getMessage() );
			}
		}
	}

	/**
	 * Adds a shutdown hook for graceful server shutdown.
	 *
	 * @param server  The Undertow server
	 * @param runtime The BoxLang runtime
	 */
	private static void addShutdownHook( Undertow server, BoxRuntime runtime ) {
		Runtime.getRuntime().addShutdownHook( new Thread( () -> {
			shuttingDown = true;
			System.out.println( "Shutting down BoxLang Server..." );

			try {
				server.stop();
				runtime.shutdown();
				System.out.println( "BoxLang Server stopped." );
			} catch ( Exception e ) {
				System.err.println( "Error during server shutdown: " + e.getMessage() );
			}
		} ) );
	}

	/**
	 * Get the current HttpServerExchange for the thread.
	 *
	 * @return The current HttpServerExchange for the thread.
	 */
	public static HttpServerExchange getCurrentExchange() {
		return currentExchange.get();
	}

	/**
	 * Set the current HttpServerExchange for the thread.
	 *
	 * @param exchange
	 */
	public static void setCurrentExchange( HttpServerExchange exchange ) {
		currentExchange.set( exchange );
	}

	/**
	 * Get the WebsocketHandler for the server.
	 *
	 * @return The WebsocketHandler for the server.
	 */
	public static WebsocketHandler getWebsocketHandler() {
		return websocketHandler;
	}

	/**
	 * --------------------------------------------------------------------
	 * Private Helpers
	 * --------------------------------------------------------------------
	 */

	/**
	 * Builds the Undertow web server with the specified parameters.
	 *
	 * @param webRootPath The path to the web root directory.
	 * @param config      The server configuration
	 *
	 * @return The built Undertow server.
	 */
	private static Undertow buildWebServer( Path webRootPath, MiniServerConfig config ) {
		Undertow.Builder builder = Undertow.builder();

		// Setup the resource manager for the web root
		resourceManager = new PathResourceManager( webRootPath, 1024, true, true );

		// Create the HTTP handler chain with encoding and welcome file handling
		HttpHandler httpHandler = createHandlerChain( webRootPath, config );

		// Apply undertow/worker/socket options from config (defaults + any user overrides)
		applyUndertowOptions( builder, config );

		// Build and return the server
		return builder
		    .addHttpListener( config.port, config.host )
		    .setHandler( httpHandler )
		    .build();
	}

	/**
	 * Creates the HTTP handler chain for processing requests.
	 *
	 * @param webRootPath The web root path
	 * @param config      The server configuration
	 *
	 * @return The configured HTTP handler chain
	 */
	private static HttpHandler createHandlerChain( Path webRootPath, MiniServerConfig config ) {
		// Create the base handler (welcome file handling and routing)
		HttpHandler	baseHandler		= new WelcomeFileHandler(
		    Handlers.predicate(
		        // If this predicate evaluates to true, we process via BoxLang, otherwise, we serve a static file
		        Predicates.parse( BOXLANG_FILE_PATTERN ),
		        new BLHandler( webRootPath.toString() ),
		        new ResourceHandler( resourceManager )
		            .setDirectoryListingEnabled( true )
		    ),
		    resourceManager,
		    DEFAULT_WELCOME_FILES
		);

		// Add security filter to block access to hidden files (starting with .)
		HttpHandler	secureHandler	= new SecurityHandler( baseHandler );
		System.out.println( "+ Security protection enabled - blocking access to hidden files (starting with .)" );

		// Conditionally add health check handler
		HttpHandler nextHandler;
		if ( config.healthCheck ) {
			nextHandler = new HealthCheckHandler( secureHandler, config.healthCheckSecure );
			String securityNote = config.healthCheckSecure ? " (detailed info restricted to localhost)" : "";
			System.out.println( "+ Health check endpoints available at /health, /health/ready, /health/live" + securityNote );
		} else {
			nextHandler = secureHandler;
		}

		// Setup the HTTP handler with encoding and welcome file handling
		HttpHandler httpHandler = new EncodingHandler(
		    new ContentEncodingRepository().addEncodingHandler(
		        "gzip",
		        new GzipEncodingProvider(),
		        GZIP_PRIORITY,
		        Predicates.parse( "request-larger-than(" + GZIP_MIN_SIZE + ")" )
		    )
		).setNext( nextHandler );

		// Add WebSocket support
		httpHandler			= new WebsocketHandler( httpHandler, WEBSOCKET_PATH );
		websocketHandler	= ( WebsocketHandler ) httpHandler;
		System.out.println( "+ WebSocket Server started" );

		// Handle rewrites if enabled
		if ( config.rewrites ) {
			System.out.println( "+ Enabling rewrites to /" + config.rewriteFileName );
			httpHandler = new FrameworkRewritesBuilder().build( Map.of( "fileName", config.rewriteFileName ) ).wrap( httpHandler );
		}

		// Wrap with exchange setter for WebSocket integration
		return createExchangeSetterHandler( httpHandler );
	}

	/**
	 * Applies Undertow server options, XNIO worker options, and XNIO socket options
	 * from the given configuration to the Undertow builder.
	 *
	 * Option names are resolved via reflection against {@link io.undertow.UndertowOptions}
	 * (for {@code undertowOptions}) and {@link org.xnio.Options} (for {@code workerOptions}
	 * and {@code socketOptions}). Unknown keys produce a warning but do not throw.
	 *
	 * @param builder The Undertow server builder
	 * @param config  The server configuration containing option maps
	 */
	private static void applyUndertowOptions( Undertow.Builder builder, MiniServerConfig config ) {
		// Server-level Undertow options
		for ( Map.Entry<String, Object> entry : config.undertowOptions.entrySet() ) {
			applyOption( entry.getKey(), entry.getValue(), UndertowOptions.class,
			    ( opt, val ) -> builder.setServerOption( opt, val ) );
		}
		// XNIO worker options
		for ( Map.Entry<String, Object> entry : config.workerOptions.entrySet() ) {
			applyOption( entry.getKey(), entry.getValue(), Options.class,
			    ( opt, val ) -> builder.setWorkerOption( opt, val ) );
		}
		// XNIO socket options
		for ( Map.Entry<String, Object> entry : config.socketOptions.entrySet() ) {
			applyOption( entry.getKey(), entry.getValue(), Options.class,
			    ( opt, val ) -> builder.setSocketOption( opt, val ) );
		}
	}

	/**
	 * Resolves a single option by name from the given options class, coerces the raw value
	 * to the option's declared type, and forwards it to the provided applier function.
	 *
	 * @param key          Option name — must match a {@code public static final Option<T>} field
	 * @param rawValue     Raw value from JSON or the defaults map
	 * @param optionsClass {@link io.undertow.UndertowOptions} or {@link org.xnio.Options}
	 * @param applier      Callback that calls the appropriate builder setter
	 */
	@SuppressWarnings( "unchecked" )
	private static void applyOption( String key, Object rawValue, Class<?> optionsClass,
	    java.util.function.BiConsumer<Option<Object>, Object> applier ) {
		try {
			java.lang.reflect.Field	field	= optionsClass.getField( key );
			Option<Object>			option	= ( Option<Object> ) field.get( null );

			// Resolve T from Option<T> via the field's generic type parameter
			Class<?>				type	= null;
			java.lang.reflect.Type	gt		= field.getGenericType();
			if ( gt instanceof java.lang.reflect.ParameterizedType ) {
				java.lang.reflect.Type[] args = ( ( java.lang.reflect.ParameterizedType ) gt ).getActualTypeArguments();
				if ( args.length > 0 && args[ 0 ] instanceof Class ) {
					type = ( Class<?> ) args[ 0 ];
				}
			}

			Object coerced;
			if ( type == Long.class ) {
				// JSON numbers may arrive as Integer if they fit — promote to Long
				coerced = ( ( Number ) rawValue ).longValue();
			} else if ( type == Integer.class ) {
				coerced = IntegerCaster.cast( rawValue );
			} else if ( type == Boolean.class ) {
				coerced = BooleanCaster.cast( rawValue );
			} else {
				coerced = StringCaster.cast( rawValue );
			}

			applier.accept( option, coerced );
			System.out.println( "  - Undertow/XNIO option applied: " + key + " = " + coerced );
		} catch ( NoSuchFieldException e ) {
			System.err.println( "Warning: Unknown Undertow/XNIO option '" + key + "' — skipping" );
		} catch ( Exception e ) {
			System.err.println( "Warning: Failed to apply Undertow/XNIO option '" + key + "': " + e.getMessage() );
		}
	}

	/**
	 * Creates an exchange setter handler that wraps the given final handler.
	 *
	 * @param finalHandler The final handler in the chain
	 *
	 * @return The exchange setter handler
	 */
	private static HttpHandler createExchangeSetterHandler( HttpHandler finalHandler ) {
		return new HttpHandler() {

			@Override
			public void handleRequest( final HttpServerExchange exchange ) throws Exception {
				try {
					// This allows the exchange to be available to the thread.
					MiniServer.setCurrentExchange( exchange );
					finalHandler.handleRequest( exchange );
				} finally {
					// Clean up after
					MiniServer.setCurrentExchange( null );
				}
			}

			@Override
			public String toString() {
				return "Exchange Setter Handler";
			}
		};
	}

	/**
	 * Normalizes the webroot path to an absolute path.
	 * If the path is relative, it resolves it against the current working directory.
	 * If the path does not exist, it will throw an IllegalArgumentException.
	 *
	 * @param webRoot The webroot path to normalize.
	 *
	 * @return The normalized absolute path.
	 *
	 * @throws IllegalArgumentException if the webroot path is invalid or doesn't exist
	 */
	private static Path normalizeWebroot( String webRoot ) {
		if ( webRoot == null || webRoot.trim().isEmpty() ) {
			// Use current directory as default
			webRoot = System.getProperty( "user.dir" );
			System.out.println( "  - No webroot specified, using current directory: " + webRoot );
		}

		try {
			Path absWebRoot = Paths.get( webRoot ).toAbsolutePath().normalize();

			// Verify webroot exists on disk
			if ( !absWebRoot.toFile().exists() ) {
				throw new IllegalArgumentException( "Web Root does not exist: " + absWebRoot.toString() );
			}

			// Verify it's a directory
			if ( !absWebRoot.toFile().isDirectory() ) {
				throw new IllegalArgumentException( "Web Root is not a directory: " + absWebRoot.toString() );
			}

			// Verify read permissions
			if ( !absWebRoot.toFile().canRead() ) {
				throw new IllegalArgumentException( "Web Root is not readable: " + absWebRoot.toString() );
			}

			return absWebRoot;
		} catch ( Exception e ) {
			if ( e instanceof IllegalArgumentException ) {
				throw e;
			}
			throw new IllegalArgumentException( "Invalid webroot path: " + webRoot + " - " + e.getMessage(), e );
		}
	}

	/**
	 * Prints version information for the BoxLang MiniServer.
	 */
	private static void printVersion() {
		var versionInfo = getVersionInfo();
		System.out.println( "Ortus BoxLang™ MiniServer v" + versionInfo.get( "version" ) );
		System.out.println( "BoxLang™ MiniServer ID: " + versionInfo.get( "boxlangId" ) );
		System.out.println( "Built On: " + versionInfo.get( "buildDate" ) );
		System.out.println( "Copyright Ortus Solutions, Corp™" );
		System.out.println( "https://boxlang.io" );
	}

	/**
	 * Get a Struct of version information from the version.properties
	 */
	public static IStruct getVersionInfo() {
		// Lazy Load the version info
		Properties properties = new Properties();
		try ( InputStream inputStream = BoxRunner.class.getResourceAsStream( "/META-INF/boxlang-miniserver/version.properties" ) ) {
			properties.load( inputStream );
		} catch ( IOException e ) {
			e.printStackTrace();
		}
		IStruct versionInfo = Struct.fromMap( properties );
		// Generate a hash of the version info as the unique boxlang runtime id
		versionInfo.put( "boxlangId", EncryptionUtil.hash( versionInfo ) );
		return versionInfo;
	}

	/**
	 * Prints help information for the BoxLang MiniServer command-line interface.
	 */
	private static void printHelp() {
		System.out.println( "🚀 BoxLang MiniServer - A fast, lightweight, pure Java web server for BoxLang applications" );
		System.out.println();
		System.out.println( "📋 USAGE:" );
		System.out.println( "  boxlang-miniserver [OPTIONS]  # 🔧 Using OS binary" );
		System.out.println( "  boxlang-miniserver [path/to/miniserver.json] [OPTIONS]  # 📄 With JSON config" );
		System.out.println( "  java -jar boxlang-miniserver.jar [OPTIONS] # 🐍 Using Java JAR" );
		System.out.println();
		System.out.println( "📄 JSON CONFIGURATION:" );
		System.out.println( "  If no arguments are provided, BoxLang MiniServer will look for a 'miniserver.json'" );
		System.out.println( "  file in the current directory. You can also specify a path to a JSON config file" );
		System.out.println( "  as the first argument. Command-line options override JSON configuration." );
		System.out.println();
		System.out.println( "  Example miniserver.json:" );
		System.out.println( "  {" );
		System.out.println( "    \"port\": 8080," );
		System.out.println( "    \"host\": \"0.0.0.0\"," );
		System.out.println( "    \"webRoot\": \"/path/to/webroot\"," );
		System.out.println( "    \"debug\": true," );
		System.out.println( "    \"rewrites\": true," );
		System.out.println( "    \"rewriteFileName\": \"index.bxm\"," );
		System.out.println( "    \"healthCheck\": true," );
		System.out.println( "    \"healthCheckSecure\": false," );
		System.out.println( "    \"envFile\": \".env.local\"," );
		System.out.println( "    \"warmupUrl\": \"/index.bxm\"," );
		System.out.println( "    \"warmupUrls\": [\"/app/init\", \"http://localhost:8080/health\"]" );
		System.out.println( "  }" );
		System.out.println();
		System.out.println( "⚙️  OPTIONS:" );
		System.out.println( "  -h, --help              ❓ Show this help message and exit" );
		System.out.println( "  -v, --version           ℹ️  Show version information and exit" );
		System.out.println( "  -p, --port <PORT>       🌐 Port to listen on (default: 8080)" );
		System.out.println( "  -w, --webroot <PATH>    📁 Path to the webroot directory (default: current directory)" );
		System.out.println( "  -d, --debug             🐛 Enable debug mode (true/false, default: false)" );
		System.out.println( "      --host <HOST>       🏠 Host to bind to (default: 0.0.0.0)" );
		System.out.println( "  -c, --configPath <PATH> ⚙️  Path to BoxLang configuration file (default: ~/.boxlang/config/boxlang.json)" );
		System.out.println( "  -s, --serverHome <PATH> 🏡 BoxLang server home directory (default: ~/.boxlang)" );
		System.out.println( "  -r, --rewrites [FILE]   🔀 Enable URL rewrites (default file: index.bxm)" );
		System.out.println( "      --health-check      ❤️  Enable health check endpoints (/health, /health/ready, /health/live)" );
		System.out.println( "      --health-check-secure 🔒 Restrict detailed health info to localhost only" );
		System.out.println( "      --warmup-url <URL>  🔥 URL to call after server starts (can be repeated for multiple URLs)" );
		System.out.println();
		System.out.println( "🌍 ENVIRONMENT VARIABLES:" );
		System.out.println( "  BOXLANG_CONFIG          📁 Path to BoxLang configuration file" );
		System.out.println( "  BOXLANG_DEBUG           🐛 Enable debug mode (true/false)" );
		System.out.println( "  BOXLANG_HOME            🏡 BoxLang server home directory" );
		System.out.println( "  BOXLANG_HOST            🏠 Host to bind to" );
		System.out.println( "  BOXLANG_PORT            🌐 Port to listen on" );
		System.out.println( "  BOXLANG_REWRITE_FILE    📄 Rewrite target file" );
		System.out.println( "  BOXLANG_REWRITES        🔀 Enable URL rewrites" );
		System.out.println( "  BOXLANG_WEBROOT         📁 Path to the webroot directory" );
		System.out.println( "  BOXLANG_HEALTH_CHECK    ❤️  Enable health check endpoints (true/false)" );
		System.out.println( "  BOXLANG_HEALTH_CHECK_SECURE 🔒 Restrict detailed health info to localhost only (true/false)" );
		System.out.println( "  JAVA_OPTS               ⚙️  Java Virtual Machine options and system properties" );
		System.out.println();
		System.out.println( "💡 EXAMPLES:" );
		System.out.println( "  # 🚀 Start server with default settings" );
		System.out.println( "  boxlang-miniserver" );
		System.out.println();
		System.out.println( "  # 📄 Start server with miniserver.json in current directory" );
		System.out.println( "  boxlang-miniserver  # Automatically loads ./miniserver.json if it exists" );
		System.out.println();
		System.out.println( "  # 📄 Start server with custom JSON configuration file" );
		System.out.println( "  boxlang-miniserver /path/to/config.json" );
		System.out.println();
		System.out.println( "  # 📄 Use JSON config with CLI override" );
		System.out.println( "  boxlang-miniserver miniserver.json --port 9090" );
		System.out.println();
		System.out.println( "  # 🌐 Start server on port 80 with custom webroot" );
		System.out.println( "  boxlang-miniserver --port 80 --webroot /var/www" );
		System.out.println();
		System.out.println( "  # 🐛 Start server with debug mode and URL rewrites enabled" );
		System.out.println( "  boxlang-miniserver --debug --rewrites" );
		System.out.println();
		System.out.println( "  # ❤️  Start server with health check endpoints enabled" );
		System.out.println( "  boxlang-miniserver --health-check" );
		System.out.println();
		System.out.println( "  # ❤️  Start server with secure health checks (detailed info only on localhost)" );
		System.out.println( "  boxlang-miniserver --health-check --health-check-secure" );
		System.out.println();
		System.out.println( "  # 🔀 Start server with custom rewrite file" );
		System.out.println( "  boxlang-miniserver --rewrites app.bxm" );
		System.out.println();
		System.out.println( "  # 🚀 Start server with JVM memory settings" );
		System.out.println( "  JAVA_OPTS=\"-Xms512m -Xmx2g\" boxlang-miniserver --port 8080" );
		System.out.println();
		System.out.println( "🏠 DEFAULT WELCOME FILES:" );
		System.out.println( "  index.bxm, index.bxs, index.cfm, index.cfs, index.htm, index.html" );
		System.out.println();
		System.out.println( "🔌 WEBSOCKET SUPPORT:" );
		System.out.println( "  WebSocket endpoint available at: ws://host:port/ws" );
		System.out.println();
		System.out.println( "ℹ More Information:" );
		System.out.println( "  📖 Documentation: https://boxlang.ortusbooks.com/" );
		System.out.println( "  💬 Community: https://community.ortussolutions.com/c/boxlang/42" );
		System.out.println( "  💾 GitHub: https://github.com/ortus-boxlang" );
		System.out.println();
	}

}
