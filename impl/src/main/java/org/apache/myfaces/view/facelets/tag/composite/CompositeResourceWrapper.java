/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.myfaces.view.facelets.tag.composite;

import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.URL;
import java.util.Map;

import jakarta.faces.FacesWrapper;
import jakarta.faces.application.Resource;
import jakarta.faces.context.FacesContext;

/**
 * The value inside composite component attribute map with the key
 * Resource.COMPONENT_RESOURCE_KEY should be a Serializable. This
 * wrapper add serialization to Resource instances, because 
 * ResourceImpl depends from the ResourceLoader used by it.
 * 
 * @author Leonardo Uribe (latest modification by $Author$)
 * @version $Revision$ $Date$
 */
public final class CompositeResourceWrapper extends Resource implements FacesWrapper<Resource>, Externalizable
{
    private static final long serialVersionUID = 8067930634887545843L;
    
    private transient Resource _delegate;
    
    public CompositeResourceWrapper()
    {
        super();
    }
    
    public CompositeResourceWrapper(Resource delegate)
    {
        super();
        this._delegate = delegate;
        setResourceName(delegate.getResourceName());
        setLibraryName(delegate.getLibraryName());
        setContentType(delegate.getContentType());
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return getWrapped().getInputStream();
    }

    @Override
    public String getRequestPath()
    {
        return getWrapped().getRequestPath();
    }

    @Override
    public Map<String, String> getResponseHeaders()
    {
        return getWrapped().getResponseHeaders();
    }

    @Override
    public URL getURL()
    {
        return getWrapped().getURL();
    }

    @Override
    public boolean userAgentNeedsUpdate(FacesContext context)
    {
        return getWrapped().userAgentNeedsUpdate(context);
    }

    @Override
    public Resource getWrapped()
    {
        if (_delegate == null)
        {
            _delegate = FacesContext.getCurrentInstance().getApplication().
                            getResourceHandler().createResource(
                                    getResourceName(), 
                                    getLibraryName(),
                                    getContentType());
        }
        return _delegate;
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
    {
        setResourceName((String) in.readObject());
        setLibraryName((String) in.readObject());
        setContentType((String) in.readObject());
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException
    {
        out.writeObject(getResourceName());
        out.writeObject(getLibraryName());
        out.writeObject(getContentType());
    }
    
    @Override
    public String toString()
    {
        // Delegate resource in this case could not be available in serialization, or
        // serialization could happen in other context. So it is better to return
        // a simple String representing the object without go into delegation.
        return ((getLibraryName() != null) ? getLibraryName() : "") + ':' +
               ((getResourceName() != null) ? getResourceName() : "") ;
    }
}
