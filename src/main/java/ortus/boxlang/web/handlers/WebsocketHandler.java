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
import io.undertow.websockets.spi.WebSocketHttpExchange;
import ortus.boxlang.web.MiniServer;

public class WebsocketHandler extends PathHandler {

	private final HttpHandler						next;
	private final Set<WebSocketChannel>				connections	= Collections.synchronizedSet( new HashSet<>() );
	private final WebSocketProtocolHandshakeHandler	webSocketProtocolHandshakeHandler;

	public WebsocketHandler( final HttpHandler next, String prefixPath ) {
		super( next );
		this.next								= next;
		this.webSocketProtocolHandshakeHandler	= new WebSocketProtocolHandshakeHandler(
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

	public void sendMessage( WebSocketChannel channel, String message ) {
		if ( channel == null || !channel.isOpen() ) {
			return;
		}
		WebSockets.sendText( message, channel, null );
	}

	public void broadcastMessage( String message ) {
		// Iterate over all open connections and send the message
		for ( WebSocketChannel channel : connections ) {
			sendMessage( channel, message );
		}
	}

}