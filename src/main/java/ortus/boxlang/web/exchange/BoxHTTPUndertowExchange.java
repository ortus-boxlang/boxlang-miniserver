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
package ortus.boxlang.web.exchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import org.xnio.channels.StreamSinkChannel;

import io.undertow.predicate.Predicate;
import io.undertow.security.api.SecurityContext;
import io.undertow.security.idm.Account;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.LocaleUtils;
import io.undertow.util.StatusCodes;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;
import ortus.boxlang.web.context.WebRequestBoxContext;
import ortus.boxlang.web.util.BlockingBufferedOutputStream;

/**
 * I implement a BoxLang HTTP exchange for Undertow
 */
public class BoxHTTPUndertowExchange implements IBoxHTTPExchange {

	/**
	 * Request attributes
	 */
	private Map<String, Object>		attributes	= new HashMap<>();

	/**
	 * Undertow response channel
	 */
	protected StreamSinkChannel		channel		= null;

	/**
	 * PrintWriter for the response that wraps the channel
	 */
	WhitespaceManagingPrintWriter	writer;

	/**
	 * The Undertow exchange for this request
	 */
	protected HttpServerExchange	exchange;

	/**
	 * The BoxLang context for this request
	 */
	protected WebRequestBoxContext	context;

	/**
	 * The list of file uploads
	 */
	List<FileUpload>				fileUploads	= new ArrayList<>();

	/**
	 * Create a new BoxLang HTTP exchange for Undertow
	 *
	 * @param exchange The Undertow exchange for this request
	 */
	public BoxHTTPUndertowExchange( HttpServerExchange exchange ) {
		this.exchange = exchange;
	}

	/**
	 * Get the response channel for this exchange
	 *
	 * @return The response channel
	 */
	public synchronized StreamSinkChannel getResponseChannel() {
		if ( channel == null ) {
			channel = exchange.getResponseChannel();
		}
		return channel;
	}

	/**
	 * Get the Undertow exchange for this request
	 *
	 * @return The Undertow exchange
	 */
	public HttpServerExchange getExchange() {
		return exchange;
	}

	@Override
	public void setWebContext( WebRequestBoxContext context ) {
		this.context = context;
	}

	@Override
	public WebRequestBoxContext getWebContext() {
		return context;
	}

	@Override
	@SuppressWarnings( "deprecation" )
	public void forward( String URI ) {
		exchange.setRequestPath( URI );
		exchange.setRelativePath( URI );
		exchange.dispatch();
	}

	@Override
	public void addResponseCookie( BoxCookie cookie ) {
		if ( !isResponseStarted() ) {
			Cookie c = new CookieImpl( cookie.getName(), cookie.getEncodedValue() );
			if ( cookie.getDomain() != null )
				c.setDomain( cookie.getDomain() );
			if ( cookie.getPath() != null )
				c.setPath( cookie.getPath() );
			c.setSecure( cookie.isSecure() );
			c.setHttpOnly( cookie.isHttpOnly() );
			if ( cookie.getMaxAge() != null )
				c.setMaxAge( cookie.getMaxAge() );
			c.setSameSite( cookie.isSameSite() );
			if ( cookie.getExpires() != null )
				c.setExpires( cookie.getExpires() );
			if ( cookie.getSameSiteMode() != null )
				c.setSameSiteMode( cookie.getSameSiteMode() );
			exchange.setResponseCookie( c );
		}
	}

	@Override
	public void addResponseHeader( String name, String value ) {
		exchange.getResponseHeaders().put( new HttpString( name ), value );
	}

	@Override
	public void flushResponseBuffer() {
		try {
			ensureResponseContentType();

			// Update this in case the content type has changed
			writer.setWhitespaceCompressionEnabled( context.isWhitespaceCompressionEnabled() );
			writer.flush();
			getResponseChannel().flush();
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}

	@Override
	public Object getRequestAttribute( String name ) {
		return attributes.get( name );
	}

	@Override
	public Map<String, Object> getRequestAttributeMap() {
		return attributes;
	}

	@Override
	public String getRequestAuthType() {
		SecurityContext securityContext = exchange.getSecurityContext();
		return securityContext != null ? securityContext.getMechanismName() : null;
	}

	@Override
	public String getRequestCharacterEncoding() {
		String contentType = exchange.getRequestHeaders().getFirst( Headers.CONTENT_TYPE );
		if ( contentType == null ) {
			return null;
		}

		return Headers.extractQuotedValueFromHeader( contentType, "charset" );
	}

	@Override
	public long getRequestContentLength() {
		final String contentLength = getRequestHeader( Headers.CONTENT_LENGTH );
		if ( contentLength == null || contentLength.isEmpty() ) {
			return -1;
		}
		return Long.parseLong( contentLength );
	}

	@Override
	public String getRequestContentType() {
		return getRequestHeader( Headers.CONTENT_TYPE );
	}

	@Override
	public String getRequestContextPath() {
		return "";
	}

	@Override
	public BoxCookie[] getRequestCookies() {
		Iterable<Cookie>	cookiesIterable	= exchange.requestCookies();
		List<Cookie>		cookies			= new ArrayList<>();
		cookiesIterable.forEach( cookies::add );

		BoxCookie[] boxCookies = new BoxCookie[ cookies.size() ];
		for ( int i = 0; i < cookies.size(); i++ ) {
			Cookie	cookie	= cookies.get( i );
			var		c		= BoxCookie.fromEncoded( cookie.getName(), cookie.getValue() );
			if ( cookie.getDomain() != null )
				c.setDomain( cookie.getDomain() );
			if ( cookie.getPath() != null )
				c.setPath( cookie.getPath() );
			c.setSecure( cookie.isSecure() );
			c.setHttpOnly( cookie.isHttpOnly() );
			if ( cookie.getMaxAge() != null )
				c.setMaxAge( cookie.getMaxAge() );
			c.setSameSite( cookie.isSameSite() );
			if ( cookie.getExpires() != null )
				c.setExpires( cookie.getExpires() );
			if ( cookie.getSameSiteMode() != null )
				c.setSameSiteMode( cookie.getSameSiteMode() );
			boxCookies[ i ] = c;
		}
		return boxCookies;
	}

	@Override
	public Map<String, String[]> getRequestHeaderMap() {
		Map<String, String[]> headers = new HashMap<>();
		exchange.getRequestHeaders().forEach( ( headerValues ) -> {
			headers.put( headerValues.getHeaderName().toString(), headerValues.toArray( new String[ 0 ] ) );
		} );
		return headers;
	}

	@Override
	public String getRequestHeader( String name ) {
		return exchange.getRequestHeaders().getFirst( name );

	}

	public String getRequestHeader( final HttpString name ) {
		HeaderMap headers = exchange.getRequestHeaders();
		return headers.getFirst( name );
	}

	@Override
	public String getRequestLocalAddr() {
		InetSocketAddress destinationAddress = exchange.getDestinationAddress();
		if ( destinationAddress == null ) {
			return "";
		}
		InetAddress address = destinationAddress.getAddress();
		if ( address == null ) {
			// this is unresolved, so we just return the host name
			return destinationAddress.getHostString();
		}
		return address.getHostAddress();
	}

	@Override
	public String getRequestLocalName() {
		return exchange.getDestinationAddress().getHostName();
	}

	@Override
	public int getRequestLocalPort() {
		return exchange.getDestinationAddress().getPort();
	}

	@Override
	public Locale getRequestLocale() {
		return getRequestLocales().nextElement();
	}

	@Override
	public Enumeration<Locale> getRequestLocales() {
		final List<String>	acceptLanguage	= exchange.getRequestHeaders().get( Headers.ACCEPT_LANGUAGE );
		List<Locale>		ret				= LocaleUtils.getLocalesFromHeader( acceptLanguage );
		if ( ret.isEmpty() ) {
			return Collections.enumeration( Collections.singletonList( Locale.getDefault() ) );
		}
		return Collections.enumeration( ret );
	}

	@Override
	public String getRequestMethod() {
		return exchange.getRequestMethod().toString();
	}

	@Override
	public Map<String, String[]> getRequestURLMap() {
		Map<String, Deque<String>>	queryParameters			= exchange.getQueryParameters();
		Map<String, String[]>		queryParametersArray	= new HashMap<>();
		for ( Map.Entry<String, Deque<String>> entry : queryParameters.entrySet() ) {
			String			key			= entry.getKey();
			Deque<String>	valueDeque	= entry.getValue();
			String[]		valueArray	= valueDeque.toArray( new String[ 0 ] );
			queryParametersArray.put( key, valueArray );
		}
		return queryParametersArray;
	}

	@Override
	public Map<String, String[]> getRequestFormMap() {
		// Store all files on disk
		System.setProperty( "io.undertow.multipart.minsize", "0" );
		FormParserFactory		parserFactory	= FormParserFactory.builder().build();
		FormDataParser			parser			= parserFactory.createParser( exchange );

		FormData				formData;
		Map<String, String[]>	formMap			= new HashMap<>();

		// If there is no parser for the request content type, this will be null
		if ( parser != null ) {

			try {
				formData = parser.parseBlocking();
			} catch ( IOException e ) {
				throw new BoxRuntimeException( "Could not parse form data", e );
			}
			for ( String key : formData ) {
				formMap.put(
				    key,
				    formData.get( key )
				        .stream()
				        .map( f -> {
					        if ( f.isFileItem() ) {
						        Path file = f.getFileItem().getFile();
						        if ( file != null ) {
							        fileUploads.add( new FileUpload( Key.of( key ), file, f.getFileName() ) );
							        return file.toString();
						        } else {
							        return f.getValue();
						        }
					        } else {
						        return f.getValue();
					        }
				        } )
				        .toArray( String[]::new ) );
			}
			return formMap;
		} else {
			return Collections.emptyMap();
		}

	}

	@Override
	public FileUpload[] getUploadData() {
		return fileUploads.toArray( new FileUpload[ 0 ] );
	}

	@Override
	public String getRequestPathInfo() {
		// In the mini server, we set this predicate context value in the BLHandler
		// prior to the request
		Map<String, Object> predicateContext = exchange.getAttachment( Predicate.PREDICATE_CONTEXT );
		if ( predicateContext.containsKey( "pathInfo" ) ) {
			return ( String ) predicateContext.get( "pathInfo" );
		} else {
			return "";
		}
	}

	@Override
	public String getRequestPathTranslated() {
		return Path.of( getWebContext().getWebRoot(), getRequestURI() ).toString();
	}

	@Override
	public String getRequestProtocol() {
		return exchange.getProtocol().toString();
	}

	@Override
	public String getRequestQueryString() {
		return exchange.getQueryString().isEmpty() ? null : exchange.getQueryString();
	}

	@Override
	public Object getRequestBody() {
		try {
			InputStream inputStream = exchange.getInputStream();
			// If this stream has already been read, return an empty string
			// TODO: Figure out how to intercept the Undertow input stream so we can access
			// it even after the form scope has been processed.
			if ( inputStream.available() == 0 ) {
				return "";
			}
			if ( isTextBasedContentType() ) {
				try ( Scanner scanner = new java.util.Scanner( inputStream ).useDelimiter( "\\A" ) ) {
					return scanner.next();
				}
			} else {
				return inputStream.readAllBytes();
			}
		} catch ( IOException e ) {
			e.printStackTrace();
			return "";
		}
	}

	@Override
	public String getRequestRemoteAddr() {
		InetSocketAddress sourceAddress = exchange.getSourceAddress();
		if ( sourceAddress == null ) {
			return "";
		}
		InetAddress address = sourceAddress.getAddress();
		if ( address == null ) {
			return sourceAddress.getHostString();
		}
		return address.getHostAddress();
	}

	@Override
	public String getRequestRemoteHost() {
		InetSocketAddress sourceAddress = exchange.getSourceAddress();
		if ( sourceAddress == null ) {
			return "";
		}
		return sourceAddress.getHostString();
	}

	@Override
	public int getRequestRemotePort() {
		return exchange.getSourceAddress().getPort();
	}

	@Override
	public String getRequestRemoteUser() {
		Principal userPrincipal = getRequestUserPrincipal();

		return userPrincipal != null ? userPrincipal.getName() : null;
	}

	@Override
	public String getRequestScheme() {
		return exchange.getRequestScheme();
	}

	@Override
	public String getRequestServerName() {
		return exchange.getHostName();
	}

	@Override
	public int getRequestServerPort() {
		return exchange.getHostPort();
	}

	@Override
	public String getRequestURI() {
		return exchange.getRelativePath();
	}

	@Override
	public StringBuffer getRequestURL() {
		return new StringBuffer( exchange.getRequestURL() );
	}

	@Override
	public Principal getRequestUserPrincipal() {
		SecurityContext	securityContext	= exchange.getSecurityContext();
		Principal		result			= null;
		Account			account			= null;
		if ( securityContext != null && ( account = securityContext.getAuthenticatedAccount() ) != null ) {
			result = account.getPrincipal();
		}
		return result;
	}

	@Override
	public String getResponseHeader( String name ) {
		return exchange.getResponseHeaders().getFirst( name );
	}

	@Override
	public Map<String, String[]> getResponseHeaderMap() {
		Map<String, String[]> headers = new HashMap<>();
		exchange.getResponseHeaders().forEach( ( headerValues ) -> {
			headers.put( headerValues.getHeaderName().toString(), headerValues.toArray( new String[ 0 ] ) );
		} );
		return headers;
	}

	@Override
	public int getResponseStatus() {
		return exchange.getStatusCode();
	}

	@Override
	public PrintWriter getResponseWriter() {
		if ( writer == null ) {
			OutputStream outputStream = new BlockingBufferedOutputStream( getResponseChannel() );
			writer = new WhitespaceManagingPrintWriter( new PrintWriter( outputStream, false ), context.isWhitespaceCompressionEnabled() );
		}
		return writer;
	}

	@Override
	public void sendResponseBinary( byte[] data ) {
		ByteBuffer bBuffer = ByteBuffer.wrap( data );
		try {
			getResponseChannel().write( bBuffer );
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}

	@Override
	public void sendResponseFile( File file ) {
		try ( FileInputStream fis = new FileInputStream( file ) ) {
			// This method doesn't buffer entire file in heap.
			// On supported kernels, it may even use sendfile directly
			FileChannel fileChannel = fis.getChannel();
			getResponseChannel().transferFrom( fileChannel, 0, fileChannel.size() );
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean isRequestSecure() {
		return exchange.isSecure();
	}

	@Override
	public void removeRequestAttribute( String name ) {
		attributes.remove( name );
	}

	@Override
	public void resetResponseBuffer() {
		context.clearBuffer();
	}

	@Override
	public void setRequestAttribute( String name, Object value ) {
		attributes.put( name, value );
	}

	@Override
	public void setResponseHeader( String name, String value ) {
		if ( !isResponseStarted() ) {
			exchange.getResponseHeaders().put( new HttpString( name ), value );
		}
	}

	@Override
	public void setResponseStatus( int sc ) {
		if ( !isResponseStarted() ) {
			exchange.setStatusCode( sc );
		}
	}

	@Override
	public void setResponseStatus( int sc, String sm ) {
		if ( !isResponseStarted() ) {
			exchange.setStatusCode( sc );
			exchange.setReasonPhrase( sm );
		}
	}

	@Override
	public BoxCookie getRequestCookie( String name ) {
		var cookies = getRequestCookies();
		for ( BoxCookie cookie : cookies ) {
			if ( cookie.getName().equalsIgnoreCase( name ) ) {
				return cookie;
			}
		}
		return null;
	}

	@Override
	public boolean isResponseStarted() {
		return exchange.isResponseStarted();
	}

	@Override
	public void reset() {
		resetResponseBuffer();
		exchange.getResponseHeaders().clear();
		exchange.setStatusCode( StatusCodes.OK );
	}
}
