/**
 * JBoss, Home of Professional Open Source. Copyright 2012, Red Hat, Inc., and individual
 * contributors as indicated by the
 *
 * @author tags. See the copyright.txt file in the distribution for a full listing of individual
 * contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * software; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.cluster.proxy.util;

import java.util.Map;

/**
 * {@code RequestFactory}
 *
 * Created on Jun 14, 2012 at 1:16:40 PM
 *
 * @author <a href="mailto:nbenothm@redhat.com">Nabil Benothman</a>
 */
public interface RequestFactory {

    /**
     * Create a new request Object with the specified parameters
     *
     * @param type the request type
     * @param wildcard tells whether the request is a wildcard
     * @param params the request parameters
     * @param jvmRoute the request jvmRoute
     * @return a new {@code Request} object
     */
    public Request create(RequestType type, boolean wildcard, Map<String, Object> params, String jvmRoute);
}
