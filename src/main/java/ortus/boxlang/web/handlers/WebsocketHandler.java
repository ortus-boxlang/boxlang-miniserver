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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.core.protocol.Handshake;
import io.undertow.websockets.core.protocol.version07.Hybi07Handshake;
import io.undertow.websockets.core.protocol.version08.Hybi08Handshake;
import io.undertow.websockets.core.protocol.version13.Hybi13Handshake;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import ortus.boxlang.web.MiniServer;

/**
 * The WebsocketHandler is a handler that handles WebSocket connections.
 * Based on Undertow.
 */
public class WebsocketHandler extends PathHandler {

	private final Set<WebSocketChannel>				connections	= Collections.synchronizedSet( new HashSet<>() );
	private final WebSocketProtocolHandshakeHandler	webSocketProtocolHandshakeHandler;

	/**
	 * Create a new WebsocketHandler.
	 *
	 * @param next       The next handler in the chain
	 * @param prefixPath The prefix path
	 */
	public WebsocketHandler( final HttpHandler next, String prefixPath ) {
		super( next );

		Set<Handshake> handshakes = new HashSet<>();
		handshakes.add( new Hybi13Handshake( Set.of( "v12.stomp", "v11.stomp", "v10.stomp" ), false ) );
		handshakes.add( new Hybi08Handshake( Set.of( "v12.stomp", "v11.stomp", "v10.stomp" ), false ) );
		handshakes.add( new Hybi07Handshake( Set.of( "v12.stomp", "v11.stomp", "v10.stomp" ), false ) );

		this.webSocketProtocolHandshakeHandler = new WebSocketProtocolHandshakeHandler(
		    handshakes,
		    new WebSocketConnectionCallback() {

			    @Override
			    public void onConnect( WebSocketHttpExchange WSexchange, WebSocketChannel channel ) {
				    // Add the new channel to the set of connections
				    connections.add( channel );
				    WebsocketReceiveListener listener = new WebsocketReceiveListener( MiniServer.getCurrentExchange(),
				        next,
				        channel );
				    channel.getReceiveSetter().set( listener );
				    channel.getCloseSetter().set( ( c ) -> {
					    connections.remove( channel );
					    listener.onClose( c );
				    } );
				    channel.resumeReceives();
			    }
		    }, next );

		// In reality, this can just be `/` and apply to all URLs, but a specific suffix
		// makes it easier to proxy at the web server level
		addPrefixPath( prefixPath, webSocketProtocolHandshakeHandler );
	}

	/**
	 * Get all connections
	 */
	public Set<WebSocketChannel> getConnections() {
		return connections;
	}

	/**
	 * Send a message to a WebSocket channel.
	 *
	 * @param channel The WebSocket channel
	 * @param message The message to send
	 */
	public void sendMessage( WebSocketChannel channel, String message ) {
		if ( channel == null || !channel.isOpen() ) {
			return;
		}
		WebSockets.sendText( message, channel, null );
	}

	/**
	 * Broadcast a message to all open connections.
	 *
	 * @param message The message to broadcast
	 */
	public void broadcastMessage( String message ) {
		// Iterate over all open connections and send the message
		for ( WebSocketChannel channel : connections ) {
			sendMessage( channel, message );
		}
	}

}
