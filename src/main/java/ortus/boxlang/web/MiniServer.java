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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.jr.ob.JSON;

import io.undertow.Handlers;
import io.undertow.Undertow;
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
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.util.EncryptionUtil;
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
	 * Default constants
	 */
	private static final int								DEFAULT_PORT			= 8080;
	private static final String								DEFAULT_HOST			= "0.0.0.0";
	private static final String								DEFAULT_REWRITE_FILE	= "index.bxm";
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
	 * Configuration class for server settings
	 */
	public static class ServerConfig {

		public int		port				= DEFAULT_PORT;
		public String	webRoot				= "";
		public Boolean	debug				= null;
		public String	host				= DEFAULT_HOST;
		public String	configPath			= null;
		public String	serverHome			= null;
		public Boolean	rewrites			= false;
		public String	rewriteFileName		= DEFAULT_REWRITE_FILE;
		public Boolean	healthCheck			= false;
		public Boolean	healthCheckSecure	= false;
		public String	envFile				= null;

		/**
		 * Validates the configuration and throws IllegalArgumentException if invalid
		 */
		public void validate() {
			if ( port < 1 || port > 65535 ) {
				throw new IllegalArgumentException( "Port must be between 1 and 65535, got: " + port );
			}
			if ( host == null || host.trim().isEmpty() ) {
				throw new IllegalArgumentException( "Host cannot be null or empty" );
			}
			if ( rewriteFileName == null || rewriteFileName.trim().isEmpty() ) {
				throw new IllegalArgumentException( "Rewrite file name cannot be null or empty" );
			}
		}
	}

	/**
	 * Main method to start the BoxLang MiniServer.
	 *
	 * @param args Command line arguments. See Help for details.
	 */
	public static void main( String[] args ) {
		try {
			// Parse configuration from environment and command line
			ServerConfig config = parseConfiguration( args );

			// Normalize and validate the webroot path
			Path absWebRoot = normalizeWebroot( config.webRoot );

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
	 * Loads environment variables from a .env file in the web root directory or from a custom env file.
	 *
	 * @param absWebRoot The absolute path to the web root directory
	 * @param config     The server configuration (may contain custom envFile path)
	 */
	private static void loadEnvFiles( Path absWebRoot, ServerConfig config ) {
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

	/**
	 * Parses configuration from environment variables and command line arguments.
	 *
	 * @param args Command line arguments
	 *
	 * @return ServerConfig with parsed values
	 */
	private static ServerConfig parseConfiguration( String[] args ) {
		Map<String, String>	envVars	= System.getenv();
		ServerConfig		config	= new ServerConfig();

		// Setup defaults from environment variables
		try {
			config.port = Integer.parseInt( envVars.getOrDefault( "BOXLANG_PORT", String.valueOf( DEFAULT_PORT ) ) );
		} catch ( NumberFormatException e ) {
			throw new IllegalArgumentException( "Invalid BOXLANG_PORT environment variable: " + envVars.get( "BOXLANG_PORT" ) );
		}

		config.webRoot				= envVars.getOrDefault( "BOXLANG_WEBROOT", "" );
		config.host					= envVars.getOrDefault( "BOXLANG_HOST", DEFAULT_HOST );
		config.configPath			= envVars.getOrDefault( "BOXLANG_CONFIG", null );
		config.serverHome			= envVars.getOrDefault( "BOXLANG_HOME", null );
		config.rewrites				= Boolean.parseBoolean( envVars.getOrDefault( "BOXLANG_REWRITES", "false" ) );
		config.rewriteFileName		= envVars.getOrDefault( "BOXLANG_REWRITE_FILE", DEFAULT_REWRITE_FILE );
		config.healthCheck			= Boolean.parseBoolean( envVars.getOrDefault( "BOXLANG_HEALTH_CHECK", "false" ) );
		config.healthCheckSecure	= Boolean.parseBoolean( envVars.getOrDefault( "BOXLANG_HEALTH_CHECK_SECURE", "false" ) );

		// Load JSON configuration if available
		// Check if first argument is a path to a JSON file
		String jsonConfigPath = null;
		if ( args.length > 0 && !args[ 0 ].startsWith( "-" ) && args[ 0 ].endsWith( ".json" ) ) {
			jsonConfigPath = args[ 0 ];
		} else {
			// Look for miniserver.json in current directory
			Path defaultJsonPath = Paths.get( System.getProperty( "user.dir" ), "miniserver.json" );
			if ( Files.exists( defaultJsonPath ) ) {
				jsonConfigPath = defaultJsonPath.toString();
			}
		}

		if ( jsonConfigPath != null ) {
			loadJsonConfiguration( config, jsonConfigPath );
		}

		// Parse command line arguments (these override JSON and environment)
		// If first arg was a JSON file path, skip it
		int startIndex = ( args.length > 0 && !args[ 0 ].startsWith( "-" ) && args[ 0 ].endsWith( ".json" ) ) ? 1 : 0;

		for ( int i = startIndex; i < args.length; i++ ) {
			String arg = args[ i ];

			if ( arg.equalsIgnoreCase( "--help" ) || arg.equalsIgnoreCase( "-h" ) ) {
				printHelp();
				System.exit( 0 );
			} else if ( arg.equalsIgnoreCase( "--version" ) || arg.equalsIgnoreCase( "-v" ) ) {
				printVersion();
				System.exit( 0 );
			} else if ( arg.equalsIgnoreCase( "--port" ) || arg.equalsIgnoreCase( "-p" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Port argument requires a value" );
				}
				try {
					config.port = Integer.parseInt( args[ ++i ] );
				} catch ( NumberFormatException e ) {
					throw new IllegalArgumentException( "Invalid port number: " + args[ i ] );
				}
			} else if ( arg.equalsIgnoreCase( "--webroot" ) || arg.equalsIgnoreCase( "-w" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Webroot argument requires a value" );
				}
				config.webRoot = args[ ++i ];
			} else if ( arg.equalsIgnoreCase( "--debug" ) || arg.equalsIgnoreCase( "-d" ) ) {
				config.debug = true;
			} else if ( arg.equalsIgnoreCase( "--host" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Host argument requires a value" );
				}
				config.host = args[ ++i ];
			} else if ( arg.equalsIgnoreCase( "--configPath" ) || arg.equalsIgnoreCase( "-c" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Config path argument requires a value" );
				}
				config.configPath = args[ ++i ];
			} else if ( arg.equalsIgnoreCase( "--serverHome" ) || arg.equalsIgnoreCase( "-s" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Server home argument requires a value" );
				}
				config.serverHome = args[ ++i ];
			} else if ( arg.equalsIgnoreCase( "--rewrites" ) || arg.equalsIgnoreCase( "-r" ) ) {
				config.rewrites = true;
				// Check if the next arg exists and is not a flag and is a file name
				if ( i + 1 < args.length && !args[ i + 1 ].startsWith( "-" ) ) {
					config.rewriteFileName = args[ ++i ];
				}
			} else if ( arg.equalsIgnoreCase( "--health-check" ) ) {
				config.healthCheck = true;
			} else if ( arg.equalsIgnoreCase( "--health-check-secure" ) ) {
				config.healthCheckSecure = true;
			} else if ( arg.startsWith( "-" ) ) {
				throw new IllegalArgumentException( "Unknown argument: " + arg );
			}
		}

		// Validate configuration
		config.validate();
		return config;
	}

	/**
	 * Loads configuration from a JSON file and applies it to the config object.
	 *
	 * @param config   The ServerConfig object to populate
	 * @param jsonPath The path to the JSON configuration file
	 */
	private static void loadJsonConfiguration( ServerConfig config, String jsonPath ) {
		try {
			Path jsonFile = Paths.get( jsonPath ).toAbsolutePath();
			if ( !Files.exists( jsonFile ) ) {
				throw new IllegalArgumentException( "JSON configuration file not found: " + jsonPath );
			}

			String	jsonContent	= Files.readString( jsonFile );
			Map		jsonConfig	= JSON.std.mapFrom( jsonContent );

			System.out.println( "+ Loading configuration from: " + jsonFile );

			// Apply JSON configuration values to config object
			if ( jsonConfig.containsKey( "port" ) ) {
				config.port = ( ( Number ) jsonConfig.get( "port" ) ).intValue();
			}
			if ( jsonConfig.containsKey( "webRoot" ) ) {
				config.webRoot = ( String ) jsonConfig.get( "webRoot" );
			}
			if ( jsonConfig.containsKey( "debug" ) ) {
				config.debug = ( Boolean ) jsonConfig.get( "debug" );
			}
			if ( jsonConfig.containsKey( "host" ) ) {
				config.host = ( String ) jsonConfig.get( "host" );
			}
			if ( jsonConfig.containsKey( "configPath" ) ) {
				config.configPath = ( String ) jsonConfig.get( "configPath" );
			}
			if ( jsonConfig.containsKey( "serverHome" ) ) {
				config.serverHome = ( String ) jsonConfig.get( "serverHome" );
			}
			if ( jsonConfig.containsKey( "rewrites" ) ) {
				config.rewrites = ( Boolean ) jsonConfig.get( "rewrites" );
			}
			if ( jsonConfig.containsKey( "rewriteFileName" ) ) {
				config.rewriteFileName = ( String ) jsonConfig.get( "rewriteFileName" );
			}
			if ( jsonConfig.containsKey( "healthCheck" ) ) {
				config.healthCheck = ( Boolean ) jsonConfig.get( "healthCheck" );
			}
			if ( jsonConfig.containsKey( "healthCheckSecure" ) ) {
				config.healthCheckSecure = ( Boolean ) jsonConfig.get( "healthCheckSecure" );
			}
			if ( jsonConfig.containsKey( "envFile" ) ) {
				config.envFile = ( String ) jsonConfig.get( "envFile" );
			}

		} catch ( IOException e ) {
			throw new IllegalArgumentException( "Failed to read JSON configuration file: " + jsonPath + " - " + e.getMessage(), e );
		} catch ( Exception e ) {
			throw new IllegalArgumentException( "Failed to parse JSON configuration file: " + jsonPath + " - " + e.getMessage(), e );
		}
	}

	/**
	 * Starts the server with the given configuration.
	 *
	 * @param config     The server configuration
	 * @param absWebRoot The absolute web root path
	 */
	private static void startServer( ServerConfig config, Path absWebRoot ) {
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
		BoxRuntime	runtime		= BoxRuntime.getInstance( config.debug, config.configPath, config.serverHome );
		IStruct		versionInfo	= runtime.getVersionInfo();
		System.out.println(
		    "  - BoxLang Version: " + versionInfo.getAsString( Key.of( "version" ) ) + " (Built On: "
		        + versionInfo.getAsString( Key.of( "buildDate" ) )
		        + ")" );
		System.out.println( "  - Runtime Started in " + ( System.currentTimeMillis() - sTime ) + "ms" );

		// Build the web server
		Undertow BLServer = buildWebServer( absWebRoot, config );

		// Add shutdown hook for graceful shutdown
		addShutdownHook( BLServer, runtime );

		// Start the server
		BLServer.start();
		long	totalStartTime	= System.currentTimeMillis() - sTime;
		String	serverUrl		= "http://" + config.host.replace( "0.0.0.0", "localhost" ) + ":" + config.port;
		System.out.println( "+ BoxLang MiniServer started in " + totalStartTime + "ms at: " + serverUrl );
		System.out.println( "Press Ctrl+C to stop the server." );
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
	private static Undertow buildWebServer( Path webRootPath, ServerConfig config ) {
		Undertow.Builder builder = Undertow.builder();

		// Setup the resource manager for the web root
		resourceManager = new PathResourceManager( webRootPath );

		// Create the HTTP handler chain with encoding and welcome file handling
		HttpHandler httpHandler = createHandlerChain( webRootPath, config );

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
	private static HttpHandler createHandlerChain( Path webRootPath, ServerConfig config ) {
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
	 * Creates a handler that sets the current exchange in ThreadLocal for WebSocket integration.
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
		System.out.println( "    \"envFile\": \".env.local\"" );
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
		System.out.println( "ℹ️ More Information:" );
		System.out.println( "  📖 Documentation: https://boxlang.ortusbooks.com/" );
		System.out.println( "  💬 Community: https://community.ortussolutions.com/c/boxlang/42" );
		System.out.println( "  💾 GitHub: https://github.com/ortus-boxlang" );
		System.out.println();
	}

}
