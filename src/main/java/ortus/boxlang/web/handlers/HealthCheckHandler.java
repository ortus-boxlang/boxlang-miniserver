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
package ortus.boxlang.web.handlers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.xnio.XnioWorker;
import org.xnio.management.XnioWorkerMXBean;

import io.undertow.Undertow;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.MetricsHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.web.MiniServer;

/**
 * Health check handler that provides server status and basic metrics.
 */
public class HealthCheckHandler implements HttpHandler {

	/**
	 * The next handler in the chain.
	 */
	private final HttpHandler	next;

	/**
	 * The server start time in milliseconds.
	 */
	private final long			startTime;

	/**
	 * Whether to restrict detailed health info to localhost only.
	 */
	private final boolean		secureMode;

	/**
	 * Creates a new health check handler.
	 *
	 * @param next The next handler in the chain
	 */
	public HealthCheckHandler( HttpHandler next ) {
		this( next, false );
	}

	/**
	 * Creates a new health check handler with optional security mode.
	 *
	 * @param next       The next handler in the chain
	 * @param secureMode Whether to restrict detailed health info to localhost only
	 */
	public HealthCheckHandler( HttpHandler next, boolean secureMode ) {
		this.next		= next;
		this.startTime	= System.currentTimeMillis();
		this.secureMode	= secureMode;
	}

	@Override
	public void handleRequest( HttpServerExchange exchange ) throws Exception {
		String path = exchange.getRequestPath();

		if ( "/health".equals( path ) || "/health/".equals( path ) ) {
			handleHealthCheck( exchange );
		} else if ( "/health/ready".equals( path ) ) {
			handleReadinessCheck( exchange );
		} else if ( "/health/live".equals( path ) ) {
			handleLivenessCheck( exchange );
		} else {
			// Not a health check endpoint, pass to next handler
			next.handleRequest( exchange );
		}
	}

	/**
	 * Handles the main health check endpoint.
	 *
	 * @param exchange The HTTP server exchange
	 */
	private void handleHealthCheck( HttpServerExchange exchange ) {
		try {
			// Check if secure mode is enabled and if the request is from localhost
			if ( secureMode && !isLocalhost( exchange ) ) {
				// Return basic status only for non-localhost requests
				String response = "{\n" +
				    "  \"status\": \"UP\",\n" +
				    "  \"timestamp\": \"" + DateTimeFormatter.ISO_INSTANT.format( Instant.now() ) + "\"\n" +
				    "}";

				exchange.getResponseHeaders().put( Headers.CONTENT_TYPE, "application/json" );
				exchange.setStatusCode( StatusCodes.OK );
				exchange.getResponseSender().send( response, StandardCharsets.UTF_8 );
				return;
			}

			BoxRuntime	runtime		= BoxRuntime.getInstance();
			IStruct		versionInfo	= runtime.getVersionInfo();

			long		uptime		= System.currentTimeMillis() - startTime;
			String		uptimeStr	= formatUptime( uptime );

			StringBuilder response = new StringBuilder();
			response.append( "{\n" );
			response.append( "  \"status\": \"UP\",\n" );
			response.append( "  \"timestamp\": \"" )
			    .append( DateTimeFormatter.ISO_INSTANT.format( Instant.now() ) )
			    .append( "\",\n" );
			response.append( "  \"uptime\": \"" ).append( uptimeStr ).append( "\",\n" );
			response.append( "  \"uptimeMs\": " ).append( uptime ).append( ",\n" );
			response.append( "  \"version\": \"" )
			    .append( versionInfo.getAsString( Key.of( "version" ) ) )
			    .append( "\",\n" );
			response.append( "  \"buildDate\": \"" )
			    .append( versionInfo.getAsString( Key.of( "buildDate" ) ) )
			    .append( "\",\n" );
			response.append( "  \"javaVersion\": \"" )
			    .append( System.getProperty( "java.version" ) )
			    .append( "\",\n" );
			response.append( "  \"memoryUsed\": " ).append( getUsedMemory() ).append( ",\n" );
			response.append( "  \"memoryMax\": " ).append( getMaxMemory() ).append( ",\n" );

			// Keep detailed runtime metrics in /health instead of exposing a separate endpoint.
			response.append( "  \"activeRequests\": " ).append( MiniServer.getActiveRequests() ).append( ",\n" );
			appendWorkerPoolMetrics( response );
			appendListenerMetrics( response );
			appendMetricsHandlerStats( response );
			appendWebSocketMetrics( response );

			response.append( "}\n" );

			sendJsonResponse( exchange, StatusCodes.OK, response.toString() );

		} catch ( Exception e ) {
			String errorResponse = String.format(
			    "{\n" +
			        "  \"status\": \"DOWN\",\n" +
			        "  \"timestamp\": \"%s\",\n" +
			        "  \"error\": \"%s\"\n" +
			        "}",
			    DateTimeFormatter.ISO_INSTANT.format( Instant.now() ),
			    e.getMessage().replace( "\"", "\\\"" )
			);

			sendJsonResponse( exchange, StatusCodes.SERVICE_UNAVAILABLE, errorResponse );
		}
	}

	/**
	 * Handles the readiness check endpoint.
	 *
	 * @param exchange The HTTP server exchange
	 */
	private void handleReadinessCheck( HttpServerExchange exchange ) {
		try {
			// If BoxLang runtime can be obtained, we are ready
			BoxRuntime.getInstance();
			sendJsonResponse( exchange, StatusCodes.OK, "{\"status\": \"READY\"}" );
		} catch ( Exception e ) {
			sendJsonResponse( exchange, StatusCodes.SERVICE_UNAVAILABLE, "{\"status\": \"NOT_READY\"}" );
		}
	}

	/**
	 * Handles the liveness check endpoint.
	 *
	 * @param exchange The HTTP server exchange
	 */
	private void handleLivenessCheck( HttpServerExchange exchange ) {
		// Simple liveness check - if we can respond, we're alive
		sendJsonResponse( exchange, StatusCodes.OK, "{\"status\": \"ALIVE\"}" );
	}

	/**
	 * Appends XnioWorker pool statistics to the JSON builder.
	 */
	private void appendWorkerPoolMetrics( StringBuilder json ) {
		json.append( "  \"workerPool\": {\n" );
		try {
			XnioWorker			worker	= MiniServer.getWorker();
			XnioWorkerMXBean	mxBean	= worker != null ? worker.getMXBean() : null;

			appendIntField( json, "    ", "core", mxBean != null ? mxBean.getCoreWorkerPoolSize() : -1, true );
			appendIntField( json, "    ", "max", mxBean != null ? mxBean.getMaxWorkerPoolSize() : -1, true );
			appendIntField( json, "    ", "current", mxBean != null ? mxBean.getWorkerPoolSize() : -1, true );
			appendIntField( json, "    ", "busy", mxBean != null ? mxBean.getBusyWorkerThreadCount() : -1, true );
			appendIntField( json, "    ", "ioThreadCount", mxBean != null ? mxBean.getIoThreadCount() : -1, true );
			appendIntField( json, "    ", "queueSize", mxBean != null ? mxBean.getWorkerQueueSize() : -1, false );
		} catch ( Exception e ) {
			// Worker MXBean not available
		}
		json.append( "  },\n" );
	}

	/**
	 * Appends per-listener connection/request/error metrics to the JSON builder.
	 */
	private void appendListenerMetrics( StringBuilder json ) {
		json.append( "  \"listeners\": [\n" );
		try {
			Undertow server = MiniServer.getServerInstance();
			if ( server == null ) {
				json.append( "  ],\n" );
				return;
			}
			List<Undertow.ListenerInfo> listenerInfos = server.getListenerInfo();

			for ( int i = 0; i < listenerInfos.size(); i++ ) {
				Undertow.ListenerInfo	li		= listenerInfos.get( i );
				ConnectorStatistics		stats	= li.getConnectorStatistics();

				json.append( "    {\n" );
				json.append( "      \"name\": \"" ).append( li.getProtcol() ).append( "\",\n" );
				appendLongField( json, "      ", "requestCount", stats != null ? stats.getRequestCount() : -1, true );
				appendLongField( json, "      ", "errorCount", stats != null ? stats.getErrorCount() : -1, true );
				appendLongField( json, "      ", "bytesSent", stats != null ? stats.getBytesSent() : -1, true );
				appendLongField( json, "      ", "bytesReceived", stats != null ? stats.getBytesReceived() : -1,
				    true );
				appendLongField( json, "      ", "activeConnections",
				    stats != null ? stats.getActiveConnections() : -1, true );
				appendLongField( json, "      ", "activeRequests",
				    stats != null ? stats.getActiveRequests() : -1, false );
				json.append( "    }" );
				if ( i < listenerInfos.size() - 1 ) {
					json.append( "," );
				}
				json.append( "\n" );
			}
		} catch ( Exception e ) {
			// Listener metrics not available
		}
		json.append( "  ],\n" );
	}

	/**
	 * Appends request-level timing/error metrics from the MetricsHandler to the JSON builder.
	 */
	private void appendMetricsHandlerStats( StringBuilder json ) {
		json.append( "  \"requestMetrics\": {\n" );
		try {
			MetricsHandler				mh		= MiniServer.getMetricsHandler();
			MetricsHandler.MetricResult	metrics	= mh != null ? mh.getMetrics() : null;

			appendLongField( json, "    ", "totalRequests", metrics != null ? metrics.getTotalRequests() : -1, true );
			appendLongField( json, "    ", "totalErrors", metrics != null ? metrics.getTotalErrors() : -1, true );
			appendLongField( json, "    ", "totalRequestTime", metrics != null ? metrics.getTotalRequestTime() : -1,
			    true );
			appendLongField( json, "    ", "maxRequestTime", metrics != null ? metrics.getMaxRequestTime() : -1, true );
			appendLongField( json, "    ", "minRequestTime", metrics != null ? metrics.getMinRequestTime() : -1, false );
		} catch ( Exception e ) {
			// MetricsHandler not available
		}
		json.append( "  },\n" );
	}

	/**
	 * Appends websocket connection metrics to the JSON builder.
	 */
	private void appendWebSocketMetrics( StringBuilder json ) {
		json.append( "  \"websocket\": {\n" );
		try {
			WebsocketHandler wsHandler = MiniServer.getWebsocketHandler();
			if ( wsHandler == null ) {
				appendLongField( json, "    ", "activeConnections", -1, true );
				appendLongField( json, "    ", "openConnections", -1, false );
			} else {
				long activeConnections = wsHandler.getConnections().size();
				long openConnections = wsHandler.getConnections().stream().filter( channel -> channel != null && channel.isOpen() ).count();
				appendLongField( json, "    ", "activeConnections", activeConnections, true );
				appendLongField( json, "    ", "openConnections", openConnections, false );
			}
		} catch ( Exception e ) {
			appendLongField( json, "    ", "activeConnections", -1, true );
			appendLongField( json, "    ", "openConnections", -1, false );
		}
		json.append( "  }\n" );
	}

	/**
	 * Appends a long field to the JSON builder.
	 *
	 * @param json          The StringBuilder
	 * @param indent        The indentation string
	 * @param key           The JSON key
	 * @param value         The long value
	 * @param trailingComma Whether to append a comma
	 */
	private void appendLongField( StringBuilder json, String indent, String key, long value,
	    boolean trailingComma ) {
		json.append( indent ).append( "\"" ).append( key ).append( "\": " ).append( value );
		if ( trailingComma ) {
			json.append( "," );
		}
		json.append( "\n" );
	}

	/**
	 * Appends an int field to the JSON builder.
	 *
	 * @param json          The StringBuilder
	 * @param indent        The indentation string
	 * @param key           The JSON key
	 * @param value         The int value
	 * @param trailingComma Whether to append a comma
	 */
	private void appendIntField( StringBuilder json, String indent, String key, int value,
	    boolean trailingComma ) {
		json.append( indent ).append( "\"" ).append( key ).append( "\": " ).append( value );
		if ( trailingComma ) {
			json.append( "," );
		}
		json.append( "\n" );
	}

	/**
	 * Sends a JSON response.
	 *
	 * @param exchange   The HTTP server exchange
	 * @param statusCode The HTTP status code
	 * @param json       The JSON response body
	 */
	private void sendJsonResponse( HttpServerExchange exchange, int statusCode, String json ) {
		exchange.setStatusCode( statusCode );
		exchange.getResponseHeaders().put( Headers.CONTENT_TYPE, "application/json; charset=utf-8" );
		exchange.getResponseSender().send( json, StandardCharsets.UTF_8 );
	}

	/**
	 * Formats uptime in a human-readable format.
	 *
	 * @param uptimeMs Uptime in milliseconds
	 *
	 * @return Formatted uptime string
	 */
	private String formatUptime( long uptimeMs ) {
		long	seconds	= uptimeMs / 1000;
		long	minutes	= seconds / 60;
		long	hours	= minutes / 60;
		long	days	= hours / 24;

		if ( days > 0 ) {
			return String.format( "%dd %dh %dm %ds", days, hours % 24, minutes % 60, seconds % 60 );
		} else if ( hours > 0 ) {
			return String.format( "%dh %dm %ds", hours, minutes % 60, seconds % 60 );
		} else if ( minutes > 0 ) {
			return String.format( "%dm %ds", minutes, seconds % 60 );
		} else {
			return String.format( "%ds", seconds );
		}
	}

	/**
	 * Checks if the request is coming from localhost.
	 *
	 * @param exchange The HTTP server exchange
	 *
	 * @return true if the request is from localhost, false otherwise
	 */
	private boolean isLocalhost( HttpServerExchange exchange ) {
		String clientAddress = exchange.getSourceAddress().getAddress().getHostAddress();
		return "127.0.0.1".equals( clientAddress ) ||
		    "0:0:0:0:0:0:0:1".equals( clientAddress ) ||
		    "::1".equals( clientAddress );
	}

	/**
	 * Gets the used memory in bytes.
	 *
	 * @return Used memory in bytes
	 */
	private long getUsedMemory() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	/**
	 * Gets the maximum available memory in bytes.
	 *
	 * @return Maximum memory in bytes
	 */
	private long getMaxMemory() {
		return Runtime.getRuntime().maxMemory();
	}
}
