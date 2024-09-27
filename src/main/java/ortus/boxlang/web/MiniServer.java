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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

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
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.web.handlers.BLHandler;
import ortus.boxlang.web.handlers.WebsocketHandler;
import ortus.boxlang.web.handlers.WelcomeFileHandler;

/**
 * The BoxLang MiniServer is a simple web server that serves BoxLang files and static files.
 *
 * The following command line arguments are supported:
 *
 * --port <port> - The port to listen on. Default is 8080.
 * --webroot <path> - The path to the webroot. Default is {@code BOXLANG_HOME/www}
 * --debug - Enable debug mode or not. Default is false.
 * --host <host> - The host to listen on. Default is {@code localhost}.
 *
 * Examples:
 *
 * <pre>
 * java -jar boxlang-miniserver.jar --webroot /path/to/webroot --debug
 * java -jar boxlang-miniserver.jar --port 80 --webroot /var/www
 * </pre>
 *
 * This will start the BoxLang MiniServer on port 8080, serving files from {@code /path/to/webroot}, and enable debug mode.
 */
public class MiniServer {

	public static boolean									shuttingDown	= false;

	public static WebsocketHandler							websocketHandler;

	private static final ThreadLocal<HttpServerExchange>	currentExchange	= new ThreadLocal<HttpServerExchange>();

	public static void main( String[] args ) {
		Map<String, String>	envVars		= System.getenv();

		// Setup default values
		int					port		= Integer.parseInt( envVars.getOrDefault( "BOXLANG_PORT", "8080" ) );
		String				webRoot		= envVars.getOrDefault( "BOXLANG_WEBROOT", "" );
		boolean				debug		= Boolean.parseBoolean( envVars.getOrDefault( "BOXLANG_DEBUG", "false" ) );
		String				host		= envVars.getOrDefault( "BOXLANG_HOST", "localhost" );
		String				configPath	= envVars.getOrDefault( "BOXLANG_CONFIG", null );
		String				serverHome	= envVars.getOrDefault( "BOXLANG_HOME", null );

		// Grab --port and --webroot from args, if they exist
		// If --debug is set, enable debug mode
		for ( int i = 0; i < args.length; i++ ) {
			if ( args[ i ].equalsIgnoreCase( "--port" ) || args[ i ].equalsIgnoreCase( "-p" ) ) {
				port = Integer.parseInt( args[ ++i ] );
			}
			if ( args[ i ].equalsIgnoreCase( "--webroot" ) || args[ i ].equalsIgnoreCase( "-w" ) ) {
				webRoot = args[ ++i ];
			}
			if ( args[ i ].equalsIgnoreCase( "--debug" ) || args[ i ].equalsIgnoreCase( "-d" ) ) {
				debug = true;
			}
			if ( args[ i ].equalsIgnoreCase( "--host" ) || args[ i ].equalsIgnoreCase( "-h" ) ) {
				host = args[ ++i ];
			}
			if ( args[ i ].equalsIgnoreCase( "--configPath" ) || args[ i ].equalsIgnoreCase( "-c" ) ) {
				configPath = args[ ++i ];
			}
			if ( args[ i ].equalsIgnoreCase( "--serverHome" ) || args[ i ].equalsIgnoreCase( "-s" ) ) {
				serverHome = args[ ++i ];
			}
		}

		// Normalize the webroot path
		Path absWebRoot = Paths.get( webRoot ).normalize();
		if ( !absWebRoot.isAbsolute() ) {
			absWebRoot = Paths.get( "" ).resolve( webRoot ).normalize().toAbsolutePath().normalize();
		}
		// Verify webroot exists on disk, else fail
		if ( !absWebRoot.toFile().exists() ) {
			System.out.println( "Web Root does not exist, cannot continue: " + absWebRoot.toString() );
			System.exit( 1 );
		}

		// Start the server
		var sTime = System.currentTimeMillis();
		System.out.println( "+ Starting BoxLang Server..." );
		System.out.println( "- Web Root: " + absWebRoot.toString() );
		System.out.println( "- Host: " + host );
		System.out.println( "- Port: " + port );
		System.out.println( "- Debug: " + debug );
		System.out.println( "- Config Path: " + configPath );
		System.out.println( "- Server Home: " + serverHome );
		System.out.println( "+ Starting BoxLang Runtime..." );

		// Startup the runtime
		BoxRuntime	runtime		= BoxRuntime.getInstance( debug, configPath, serverHome );
		IStruct		versionInfo	= runtime.getVersionInfo();
		System.out.println(
		    "- BoxLang Version: " + versionInfo.getAsString( Key.of( "version" ) ) + " (Built On: " + versionInfo.getAsString( Key.of( "buildDate" ) ) + ")" );
		Undertow.Builder	builder			= Undertow.builder();
		ResourceManager		resourceManager	= new PathResourceManager( absWebRoot );

		System.out.println( "+ Runtime Started in " + ( System.currentTimeMillis() - sTime ) + "ms" );

		HttpHandler httpHandler = new EncodingHandler(
		    new ContentEncodingRepository().addEncodingHandler(
		        "gzip", new GzipEncodingProvider(), 50, Predicates.parse( "request-larger-than(1500)" )
		    )
		)
		    .setNext( new WelcomeFileHandler(
		        Handlers.predicate(
		            // If this predicate evaluates to true, we process via BoxLang, otherwise, we serve a static file
		            Predicates.parse( "regex( '^(/.+?\\.cfml|/.+?\\.cf[cms]|.+?\\.bx[ms]{0,1})(/.*)?$' )" ),
		            new BLHandler( absWebRoot.toString() ),
		            new ResourceHandler( resourceManager )
		                .setDirectoryListingEnabled( true ) ),
		        resourceManager,
		        List.of( "index.bxm", "index.bxs", "index.cfm", "index.cfs", "index.htm", "index.html" )
		    ) );

		System.out.println( "+ WebSocket Server started" );
		httpHandler			= new WebsocketHandler( httpHandler, "/ws" );
		websocketHandler	= ( WebsocketHandler ) httpHandler;

		final HttpHandler finalHttpHandler = httpHandler;
		httpHandler = new HttpHandler() {

			@Override
			public void handleRequest( final HttpServerExchange exchange ) throws Exception {
				try {
					// This allows the exchange to be available to the thread.
					MiniServer.setCurrentExchange( exchange );
					finalHttpHandler.handleRequest( exchange );
				} finally {
					// Clean up after
					MiniServer.setCurrentExchange( null );
				}
			}

			@Override
			public String toString() {
				return "Websocket Exchange Setter Handler";
			}
		};

		// Build out the server
		Undertow BLServer = builder
		    .addHttpListener( port, host )
		    .setHandler( httpHandler )
		    .build();

		// Add a shutdown hook to stop the server
		// Add shutdown hook to gracefully stop the server
		Runtime.getRuntime().addShutdownHook( new Thread( () -> {
			shuttingDown = true;
			System.out.println( "Shutting down BoxLang Server..." );
			BLServer.stop();
			runtime.shutdown();
			System.out.println( "BoxLang Server stopped." );
		} ) );

		// Startup the server
		System.out.println( "+ BoxLang MiniServer started in " + ( System.currentTimeMillis() - sTime ) + "ms" );
		System.out.println( "+ BoxLang MiniServer started at: http://" + host + ":" + port );
		System.out.println( "Press Ctrl+C to stop the server." );
		BLServer.start();
	}

	public static HttpServerExchange getCurrentExchange() {
		return currentExchange.get();
	}

	public static void setCurrentExchange( HttpServerExchange exchange ) {
		currentExchange.set( exchange );
	}

	public static WebsocketHandler getWebsocketHandler() {
		return websocketHandler;
	}

}
