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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.undertow.Handlers;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.server.handlers.builder.PredicatedHandler;
import io.undertow.server.handlers.builder.PredicatedHandlersParser;
import ortus.boxlang.web.MiniServer;

/**
 * @author Brad Wood
 */
public class FrameworkRewritesBuilder implements HandlerBuilder {

	public String name() {
		return "framework-rewrite";
	}

	public Map<String, Class<?>> parameters() {
		Map<String, Class<?>> params = new HashMap<>();
		params.put( "fileName", String[].class );
		return params;
	}

	public Set<String> requiredParameters() {
		return Collections.singleton( "fileName" );
	}

	public String defaultParameter() {
		return "fileName";
	}

	public HandlerWrapper build( final Map<String, Object> config ) {
		String fileName = ( String ) config.get( "fileName" );

		return new HandlerWrapper() {

			@Override
			public HttpHandler wrap( HttpHandler toWrap ) {
				List<PredicatedHandler> ph = PredicatedHandlersParser.parse(
				    "not regex('^/(ws)/.*')"
				        + "and not path(/favicon.ico)"
				        + " and not is-file"
				        + " and not is-directory -> rewrite( '/" + fileName + "%{DECODED_REQUEST_PATH}' )",
				    MiniServer.class.getClassLoader()
				);
				return Handlers.predicates( ph, toWrap );
			}
		};
	}
}
