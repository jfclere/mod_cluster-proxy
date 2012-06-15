/**
 * JBoss, Home of Professional Open Source. Copyright 2012, Red Hat, Inc., and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of individual
 * contributors.
 * 
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.jboss.cluster.proxy.util.impl;

import java.util.Collections;
import java.util.Map;

import org.jboss.cluster.proxy.util.Request;
import org.jboss.cluster.proxy.util.RequestType;

/**
 * {@code RequestImpl}
 * 
 * Created on Jun 14, 2012 at 1:02:33 PM
 * 
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
public class DefaultRequestImpl implements Request {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7107364666635260031L;
	private RequestType type;
	private boolean wildcard;
	private Map<String, Object> parameters;
	private String jvmRoute;

	/**
	 * Create a new instance of {@code DefaultRequestImpl}
	 * 
	 * @param type
	 * @param wildcard
	 * @param parameters
	 * @param jvmRoute
	 */
	public DefaultRequestImpl(RequestType type, boolean wildcard, Map<String, Object> parameters,
			String jvmRoute) {
		this.type = type;
		this.wildcard = wildcard;
		this.parameters = Collections.unmodifiableMap(parameters);
		this.jvmRoute = jvmRoute;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jboss.modcluster.proxy.util.Request#getType()
	 */
	@Override
	public RequestType getType() {
		return this.type;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jboss.modcluster.proxy.util.Request#isWilcard()
	 */
	@Override
	public boolean isWildcard() {
		return this.wildcard;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jboss.modcluster.proxy.util.Request#getParameters()
	 */
	@Override
	public Map<String, Object> getParameters() {
		return this.parameters;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.jboss.modcluster.proxy.util.Request#getJvmRoute()
	 */
	public String getJvmRoute() {
		return this.jvmRoute;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getClass().getName());
		sb.append("Request:{Type=").append(this.type);
		sb.append(", Wildcard=").append(this.wildcard);
		sb.append(", JvmRoute=").append(this.jvmRoute);
		sb.append(", Parameters=").append(this.parameters);
		sb.append("}");

		return sb.toString();
	}
}
