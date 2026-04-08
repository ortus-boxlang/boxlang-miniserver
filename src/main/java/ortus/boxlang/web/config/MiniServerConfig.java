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
package ortus.boxlang.web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.jr.ob.JSON;

import ortus.boxlang.runtime.dynamic.casters.BooleanCaster;
import ortus.boxlang.runtime.dynamic.casters.IntegerCaster;
import ortus.boxlang.runtime.dynamic.casters.StringCaster;

/**
 * Configuration for the BoxLang MiniServer.
 *
 * Configuration is applied in the following order (highest priority last):
 * 1. Defaults (hardcoded in this class)
 * 2. Environment variables (BOXLANG_*)
 * 3. JSON configuration file (miniserver.json or explicitly specified)
 * 4. Command-line arguments
 *
 * Undertow/XNIO tuning is available via three nested maps in the JSON config:
 * - {@code undertowOptions} → {@link io.undertow.UndertowOptions} constants → {@code builder.setServerOption()}
 * - {@code workerOptions} → {@link org.xnio.Options} constants → {@code builder.setWorkerOption()}
 * - {@code socketOptions} → {@link org.xnio.Options} constants → {@code builder.setSocketOption()}
 *
 * Example miniserver.json:
 *
 * <pre>
 * {
 *   "port": 8080,
 *   "undertowOptions": {
 *     "MAX_ENTITY_SIZE": 104857600,
 *     "MULTIPART_MAX_ENTITY_SIZE": 524288000
 *   },
 *   "workerOptions": {
 *     "WORKER_TASK_MAX_THREADS": 200
 *   },
 *   "socketOptions": {
 *     "TCP_NODELAY": true
 *   }
 * }
 * </pre>
 */
public class MiniServerConfig {

	// -------------------------------------------------------------------------
	// Server Defaults
	// -------------------------------------------------------------------------

	/** Default port to listen on */
	public static final int					DEFAULT_PORT			= 8080;

	/** Default host to bind to */
	public static final String				DEFAULT_HOST			= "0.0.0.0";

	/** Default rewrite target file when URL rewrites are enabled */
	public static final String				DEFAULT_REWRITE_FILE	= "index.bxm";

	// -------------------------------------------------------------------------
	// Default Undertow / XNIO Option Maps
	//
	// These replace Undertow's built-in 2 MB limits with sensible values for
	// a general-purpose web server. Users can override any key via miniserver.json.
	// -------------------------------------------------------------------------

	/**
	 * Default server-level Undertow options applied via {@code builder.setServerOption()}.
	 *
	 * <ul>
	 * <li>{@code MAX_ENTITY_SIZE} – 25 MB: maximum size of any HTTP entity body (JSON, form data, etc.)</li>
	 * <li>{@code MULTIPART_MAX_ENTITY_SIZE} – 100 MB: maximum size for multipart/form-data file uploads;
	 * per Undertow docs this should be larger than {@code MAX_ENTITY_SIZE}</li>
	 * </ul>
	 *
	 * The map is unmodifiable. Instance copies are made on construction so user JSON values
	 * can override individual keys without affecting other instances.
	 */
	public static final Map<String, Object>	DEFAULT_UNDERTOW_OPTIONS;

	/**
	 * Default worker-level XNIO options applied via {@code builder.setWorkerOption()}.
	 * Empty by default; users can populate via {@code workerOptions} in miniserver.json.
	 *
	 * Example keys: {@code WORKER_TASK_CORE_THREADS}, {@code WORKER_TASK_MAX_THREADS},
	 * {@code WORKER_IO_THREADS}, {@code WORKER_TASK_KEEPALIVE}
	 */
	public static final Map<String, Object>	DEFAULT_WORKER_OPTIONS;

	/**
	 * Default socket-level XNIO options applied via {@code builder.setSocketOption()}.
	 * Empty by default; users can populate via {@code socketOptions} in miniserver.json.
	 *
	 * Example keys: {@code TCP_NODELAY}, {@code RECEIVE_BUFFER}, {@code SEND_BUFFER},
	 * {@code KEEP_ALIVE}, {@code BACKLOG}, {@code READ_TIMEOUT}, {@code WRITE_TIMEOUT}
	 */
	public static final Map<String, Object>	DEFAULT_SOCKET_OPTIONS;

	static {
		Map<String, Object> undertow = new LinkedHashMap<>();
		// Replace Undertow's 2 MB default with 25 MB for general HTTP entity bodies
		undertow.put( "MAX_ENTITY_SIZE", 25L * 1024L * 1024L );
		// Replace Undertow's 2 MB multipart default with 100 MB for file uploads.
		// Per Undertow docs, this should be larger than MAX_ENTITY_SIZE.
		undertow.put( "MULTIPART_MAX_ENTITY_SIZE", 100L * 1024L * 1024L );
		DEFAULT_UNDERTOW_OPTIONS	= Collections.unmodifiableMap( undertow );
		DEFAULT_WORKER_OPTIONS		= Collections.unmodifiableMap( new LinkedHashMap<>() );
		DEFAULT_SOCKET_OPTIONS		= Collections.unmodifiableMap( new LinkedHashMap<>() );
	}

	// -------------------------------------------------------------------------
	// Instance Fields
	// -------------------------------------------------------------------------

	/** Port to listen on. Default: {@value #DEFAULT_PORT} */
	public int					port				= DEFAULT_PORT;

	/** Web root path. Default: current working directory */
	public String				webRoot				= "";

	/** Enable BoxLang debug mode. Null means not set (runtime decides). */
	public Boolean				debug				= null;

	/** Host address to bind to. Default: {@value #DEFAULT_HOST} */
	public String				host				= DEFAULT_HOST;

	/** Path to the BoxLang configuration file. Null means use BoxLang defaults. */
	public String				configPath			= null;

	/** BoxLang server home directory. Null means use BoxLang defaults. */
	public String				serverHome			= null;

	/** Enable URL rewrites. Default: false */
	public Boolean				rewrites			= false;

	/** Rewrite target file when rewrites are enabled. Default: {@value #DEFAULT_REWRITE_FILE} */
	public String				rewriteFileName		= DEFAULT_REWRITE_FILE;

	/** Enable health check endpoints. Default: false */
	public Boolean				healthCheck			= false;

	/** Restrict detailed health info to localhost only. Default: false */
	public Boolean				healthCheckSecure	= false;

	/** Path to a .env file to load. Null means auto-detect from webroot. */
	public String				envFile				= null;

	/** List of URLs to warm up after server starts. */
	public List<String>			warmupUrls			= new ArrayList<>();

	/** CDS mode: when true startServer() exits immediately after detection (used for AppCDS archive generation). Default: false */
	public Boolean				cds					= false;

	/**
	 * Server-level Undertow options forwarded to {@code builder.setServerOption()}.
	 * Keys must match constant names in {@link io.undertow.UndertowOptions}.
	 * Initialized from {@link #DEFAULT_UNDERTOW_OPTIONS}; user JSON values override individual keys.
	 */
	public Map<String, Object>	undertowOptions		= new LinkedHashMap<>( DEFAULT_UNDERTOW_OPTIONS );

	/**
	 * Worker-level XNIO options forwarded to {@code builder.setWorkerOption()}.
	 * Keys must match constant names in {@link org.xnio.Options}.
	 * Initialized from {@link #DEFAULT_WORKER_OPTIONS}.
	 */
	public Map<String, Object>	workerOptions		= new LinkedHashMap<>( DEFAULT_WORKER_OPTIONS );

	/**
	 * Socket-level XNIO options forwarded to {@code builder.setSocketOption()}.
	 * Keys must match constant names in {@link org.xnio.Options}.
	 * Initialized from {@link #DEFAULT_SOCKET_OPTIONS}.
	 */
	public Map<String, Object>	socketOptions		= new LinkedHashMap<>( DEFAULT_SOCKET_OPTIONS );

	// -------------------------------------------------------------------------
	// Factory
	// -------------------------------------------------------------------------

	/**
	 * Parses a {@link MiniServerConfig} from environment variables and command-line arguments.
	 *
	 * Processing order (lowest → highest priority):
	 * <ol>
	 * <li>Field defaults</li>
	 * <li>Environment variables ({@code BOXLANG_*})</li>
	 * <li>JSON config file (auto-detected {@code miniserver.json} or first CLI arg ending in {@code .json})</li>
	 * <li>Command-line flags</li>
	 * </ol>
	 *
	 * @param args Command-line arguments passed to the application
	 *
	 * @return A validated {@link MiniServerConfig}
	 *
	 * @throws IllegalArgumentException if arguments are invalid or a required file is missing
	 */
	public static MiniServerConfig fromArgs( String[] args ) {
		Map<String, String>	envVars	= System.getenv();
		MiniServerConfig	config	= new MiniServerConfig();

		// 1. Environment variables
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

		// 2. JSON configuration file
		boolean	firstArgIsJsonFile	= args.length > 0 && !args[ 0 ].startsWith( "-" ) && args[ 0 ].endsWith( ".json" );
		String	jsonConfigPath		= null;

		if ( firstArgIsJsonFile ) {
			Path jsonPath = Paths.get( args[ 0 ] ).toAbsolutePath();
			if ( Files.exists( jsonPath ) ) {
				jsonConfigPath = args[ 0 ];
			} else {
				throw new IllegalArgumentException( "JSON configuration file not found: " + args[ 0 ] );
			}
		} else {
			// Auto-detect miniserver.json in the current directory
			Path defaultJsonPath = Paths.get( System.getProperty( "user.dir" ), "miniserver.json" );
			if ( Files.exists( defaultJsonPath ) ) {
				jsonConfigPath = defaultJsonPath.toString();
			}
		}

		if ( jsonConfigPath != null ) {
			config.applyJson( jsonConfigPath );
		}

		// 3. Command-line arguments (override everything above)
		int startIndex = firstArgIsJsonFile ? 1 : 0;

		for ( int i = startIndex; i < args.length; i++ ) {
			String arg = args[ i ];

			if ( arg.equalsIgnoreCase( "--help" ) || arg.equalsIgnoreCase( "-h" ) ) {
				// Signal to caller that help was requested
				return null;
			} else if ( arg.equalsIgnoreCase( "--version" ) || arg.equalsIgnoreCase( "-v" ) ) {
				// Signal to caller that version was requested
				return null;
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
				// Check if the next arg exists and is not a flag — treat it as the rewrite file name
				if ( i + 1 < args.length && !args[ i + 1 ].startsWith( "-" ) ) {
					config.rewriteFileName = args[ ++i ];
				}
			} else if ( arg.equalsIgnoreCase( "--health-check" ) ) {
				config.healthCheck = true;
			} else if ( arg.equalsIgnoreCase( "--health-check-secure" ) ) {
				config.healthCheckSecure = true;
			} else if ( arg.equalsIgnoreCase( "--warmup-url" ) ) {
				if ( i + 1 >= args.length ) {
					throw new IllegalArgumentException( "Warmup URL argument requires a value" );
				}
				config.warmupUrls.add( args[ ++i ] );
			} else if ( arg.equalsIgnoreCase( "--cds" ) ) {
				config.cds = true;
			} else if ( arg.startsWith( "-" ) ) {
				throw new IllegalArgumentException( "Unknown argument: " + arg );
			}
		}

		// Convention: auto-detect .boxlang.json in the current working directory
		// Only applies if configPath has not been set via env var, JSON config, or CLI
		if ( config.configPath == null ) {
			Path conventionConfig = Paths.get( System.getProperty( "user.dir" ), ".boxlang.json" );
			if ( Files.exists( conventionConfig ) ) {
				config.configPath = conventionConfig.toAbsolutePath().toString();
				System.out.println( "+ Detected BoxLang config via convention: " + config.configPath );
			}
		}

		config.validate();
		return config;
	}

	// -------------------------------------------------------------------------
	// JSON Loading
	// -------------------------------------------------------------------------

	/**
	 * Loads configuration from a JSON file and merges it into this instance.
	 * Existing values are overwritten only when the JSON key is present and non-null.
	 *
	 * The three Undertow/XNIO option maps are merged: JSON keys are added (or overwrite)
	 * individual entries, but keys not present in the JSON are preserved from the defaults.
	 * Option name keys are upper-cased automatically, so {@code "max_entity_size"} and
	 * {@code "MAX_ENTITY_SIZE"} are treated identically.
	 *
	 * @param jsonPath Path to the JSON configuration file
	 *
	 * @throws IllegalArgumentException if the file cannot be read or parsed
	 */
	public void applyJson( String jsonPath ) {
		try {
			Path jsonFile = Paths.get( jsonPath ).toAbsolutePath();
			if ( !Files.exists( jsonFile ) ) {
				throw new IllegalArgumentException( "JSON configuration file not found: " + jsonPath );
			}

			String				jsonContent	= Files.readString( jsonFile );
			Map<String, Object>	jsonConfig	= JSON.std.mapFrom( jsonContent );

			System.out.println( "+ Loading configuration from: " + jsonFile );

			if ( jsonConfig.containsKey( "port" ) && jsonConfig.get( "port" ) != null ) {
				port = IntegerCaster.cast( jsonConfig.get( "port" ) );
			}
			if ( jsonConfig.containsKey( "webRoot" ) && jsonConfig.get( "webRoot" ) != null ) {
				webRoot = StringCaster.cast( jsonConfig.get( "webRoot" ) );
			}
			if ( jsonConfig.containsKey( "debug" ) && jsonConfig.get( "debug" ) != null ) {
				debug = BooleanCaster.cast( jsonConfig.get( "debug" ) );
			}
			if ( jsonConfig.containsKey( "host" ) && jsonConfig.get( "host" ) != null ) {
				host = StringCaster.cast( jsonConfig.get( "host" ) );
			}
			if ( jsonConfig.containsKey( "configPath" ) && jsonConfig.get( "configPath" ) != null ) {
				configPath = StringCaster.cast( jsonConfig.get( "configPath" ) );
			}
			if ( jsonConfig.containsKey( "serverHome" ) && jsonConfig.get( "serverHome" ) != null ) {
				serverHome = StringCaster.cast( jsonConfig.get( "serverHome" ) );
			}
			if ( jsonConfig.containsKey( "rewrites" ) && jsonConfig.get( "rewrites" ) != null ) {
				rewrites = BooleanCaster.cast( jsonConfig.get( "rewrites" ) );
			}
			if ( jsonConfig.containsKey( "rewriteFileName" ) && jsonConfig.get( "rewriteFileName" ) != null ) {
				rewriteFileName = StringCaster.cast( jsonConfig.get( "rewriteFileName" ) );
			}
			if ( jsonConfig.containsKey( "healthCheck" ) && jsonConfig.get( "healthCheck" ) != null ) {
				healthCheck = BooleanCaster.cast( jsonConfig.get( "healthCheck" ) );
			}
			if ( jsonConfig.containsKey( "healthCheckSecure" ) && jsonConfig.get( "healthCheckSecure" ) != null ) {
				healthCheckSecure = BooleanCaster.cast( jsonConfig.get( "healthCheckSecure" ) );
			}
			if ( jsonConfig.containsKey( "envFile" ) && jsonConfig.get( "envFile" ) != null ) {
				envFile = StringCaster.cast( jsonConfig.get( "envFile" ) );
			}

			// Handle warmupUrl (single string shorthand) or warmupUrls (array)
			if ( jsonConfig.containsKey( "warmupUrl" ) && jsonConfig.get( "warmupUrl" ) != null ) {
				warmupUrls.add( StringCaster.cast( jsonConfig.get( "warmupUrl" ) ) );
			}
			if ( jsonConfig.containsKey( "warmupUrls" ) && jsonConfig.get( "warmupUrls" ) != null ) {
				Object warmupUrlsObj = jsonConfig.get( "warmupUrls" );
				if ( warmupUrlsObj instanceof List ) {
					@SuppressWarnings( "unchecked" )
					List<Object> urlList = ( List<Object> ) warmupUrlsObj;
					for ( Object url : urlList ) {
						warmupUrls.add( StringCaster.cast( url ) );
					}
				}
			}

			// Undertow server options — keys upper-cased, merged on top of defaults
			mergeOptionMap( jsonConfig, "undertowOptions", undertowOptions );

			// XNIO worker options — keys upper-cased, merged on top of defaults
			mergeOptionMap( jsonConfig, "workerOptions", workerOptions );

			// XNIO socket options — keys upper-cased, merged on top of defaults
			mergeOptionMap( jsonConfig, "socketOptions", socketOptions );

		} catch ( IOException e ) {
			throw new IllegalArgumentException( "Failed to read JSON configuration file: " + jsonPath + " - " + e.getMessage(), e );
		} catch ( IllegalArgumentException e ) {
			throw e;
		} catch ( Exception e ) {
			throw new IllegalArgumentException( "Failed to parse JSON configuration file: " + jsonPath + " - " + e.getMessage(), e );
		}
	}

	// -------------------------------------------------------------------------
	// Validation
	// -------------------------------------------------------------------------

	/**
	 * Validates the configuration and throws {@link IllegalArgumentException} if invalid.
	 *
	 * @throws IllegalArgumentException if any field has an invalid value
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

	// -------------------------------------------------------------------------
	// Private Helpers
	// -------------------------------------------------------------------------

	/**
	 * Merges a named sub-map from the JSON config into the given target map.
	 * Keys are upper-cased before being stored. Ignores entries whose values are null.
	 *
	 * @param jsonConfig The top-level parsed JSON map
	 * @param key        The key under which the sub-map lives in the JSON
	 * @param target     The instance map to merge entries into
	 */
	@SuppressWarnings( "unchecked" )
	private void mergeOptionMap( Map<String, Object> jsonConfig, String key, Map<String, Object> target ) {
		if ( !jsonConfig.containsKey( key ) || jsonConfig.get( key ) == null ) {
			return;
		}
		Object raw = jsonConfig.get( key );
		if ( ! ( raw instanceof Map ) ) {
			System.err.println( "Warning: '" + key + "' in miniserver.json is not an object — skipping" );
			return;
		}
		Map<String, Object> source = ( Map<String, Object> ) raw;
		for ( Map.Entry<String, Object> entry : source.entrySet() ) {
			if ( entry.getValue() != null ) {
				target.put( entry.getKey().toUpperCase(), entry.getValue() );
			}
		}
	}

}
