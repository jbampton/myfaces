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
package org.apache.myfaces.application;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.faces.annotation.View;
import jakarta.faces.application.Resource;
import jakarta.faces.application.ResourceHandler;
import jakarta.faces.application.ResourceVisitOption;
import jakarta.faces.application.ResourceWrapper;
import jakarta.faces.application.ViewHandler;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewDeclarationLanguage;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.myfaces.cdi.util.CDIUtils;
import org.apache.myfaces.config.webparameters.MyfacesConfig;
import org.apache.myfaces.core.api.shared.lang.Assert;
import org.apache.myfaces.core.api.shared.lang.LocaleUtils;
import org.apache.myfaces.core.api.shared.lang.SharedStringBuilder;
import org.apache.myfaces.renderkit.html.util.ResourceUtils;
import org.apache.myfaces.resource.ContractResource;
import org.apache.myfaces.resource.ContractResourceLoader;
import org.apache.myfaces.resource.ResourceCachedInfo;
import org.apache.myfaces.resource.ResourceHandlerCache;
import org.apache.myfaces.resource.ResourceHandlerCache.ResourceValue;
import org.apache.myfaces.resource.ResourceHandlerSupport;
import org.apache.myfaces.resource.ResourceImpl;
import org.apache.myfaces.resource.ResourceLoader;
import org.apache.myfaces.resource.ResourceMeta;
import org.apache.myfaces.resource.ResourceValidationUtils;
import org.apache.myfaces.util.ExternalContextUtils;
import org.apache.myfaces.util.WebConfigParamUtils;
import org.apache.myfaces.util.lang.ClassUtils;
import org.apache.myfaces.util.lang.SkipMatchIterator;
import org.apache.myfaces.util.lang.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * DOCUMENT ME!
 *
 * @author Simon Lessard (latest modification by $Author$)
 * 
 * @version $Revision$ $Date$
 */
public class ResourceHandlerImpl extends ResourceHandler
{
    private static final String IS_RESOURCE_REQUEST = "org.apache.myfaces.IS_RESOURCE_REQUEST";

    private static final Logger log = Logger.getLogger(ResourceHandlerImpl.class.getName());

    public static final Pattern LIBRARY_VERSION_CHECKER = Pattern.compile("\\p{Digit}+(_\\p{Digit}*)*");
    public static final Pattern RESOURCE_VERSION_CHECKER = Pattern.compile("\\p{Digit}+(_\\p{Digit}*)*\\..*");    
    
    public final static String RENDERED_RESOURCES_SET = "org.apache.myfaces.RENDERED_RESOURCES_SET";

    private static final String SHARED_STRING_BUILDER = ResourceHandlerImpl.class.getName() + ".SHARED_STRING_BUILDER";
    
    private static final String[] FACELETS_VIEW_MAPPINGS_PARAM = {ViewHandler.FACELETS_VIEW_MAPPINGS_PARAM_NAME,
            "facelets.VIEW_MAPPINGS"};
    
    private ResourceHandlerSupport _resourceHandlerSupport;
    private ResourceHandlerCache _resourceHandlerCache;
    private Boolean _allowSlashLibraryName;
    private int _resourceBufferSize = -1;
    private String[] _excludedResourceExtensions;
    private Set<String> _viewSuffixes = null;

    @Override
    public Resource createResource(String resourceName)
    {
        return createResource(resourceName, null);
    }

    @Override
    public Resource createResource(String resourceName, String libraryName)
    {
        return createResource(resourceName, libraryName, null);
    }

    @Override
    public Resource createResource(String resourceName, String libraryName, String contentType)
    {
        Assert.notNull(resourceName, "resourceName");
        
        Resource resource = null;
        
        if (resourceName.length() == 0)
        {
            return null;
        }

        if (resourceName.charAt(0) == '/')
        {
            // If resourceName starts with '/', remove that character because it
            // does not have any meaning (with and without should point to the 
            // same resource).
            resourceName = resourceName.substring(1);
        }        
        if (!ResourceValidationUtils.isValidResourceName(resourceName))
        {
            return null;
        }
        if (libraryName != null && !ResourceValidationUtils.isValidLibraryName(
                libraryName, isAllowSlashesLibraryName()))
        {
            return null;
        }
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (contentType == null)
        {
            //Resolve contentType using ExternalContext.getMimeType
            contentType = facesContext.getExternalContext().getMimeType(resourceName);
        }

        final String localePrefix = getLocalePrefixForLocateResource(facesContext);
        final List<String> contracts = facesContext.getResourceLibraryContracts(); 
        String contractPreferred = getContractNameForLocateResource(facesContext);
        ResourceValue resourceValue = null;

        // Check cache:
        //
        // Contracts are on top of everything, because it is a concept that defines
        // resources in a application scope concept. It means all resources in
        // /resources or /META-INF/resources can be overridden using a contract. Note
        // it also means resources under /META-INF/flows can also be overridden using
        // a contract.
        
        // Check first the preferred contract if any. If not found, try the remaining
        // contracts and finally if not found try to found a resource without a 
        // contract name.
        if (contractPreferred != null)
        {
            resourceValue = getResourceHandlerCache().getResource(
                    resourceName, libraryName, contentType, localePrefix, contractPreferred);
        }
        if (resourceValue == null && !contracts.isEmpty())
        {
            // Try to get resource but try with a contract name
            for (String contract : contracts)
            {
                resourceValue = getResourceHandlerCache().getResource(
                    resourceName, libraryName, contentType, localePrefix, contract);
                if (resourceValue != null)
                {
                    break;
                }
            }
        }
        // Only if no contract preferred try without it.
        if (resourceValue == null)
        {
            // Try to get resource without contract name
            resourceValue = getResourceHandlerCache().getResource(resourceName, libraryName, contentType, localePrefix);
        }
        
        if(resourceValue != null)
        {
            resource = new ResourceImpl(resourceValue.getResourceMeta(), resourceValue.getResourceLoader(),
                    getResourceHandlerSupport(), contentType, 
                    resourceValue.getCachedInfo() != null ? resourceValue.getCachedInfo().getURL() : null, 
                    resourceValue.getCachedInfo() != null ? resourceValue.getCachedInfo().getRequestPath() : null);
        }
        else
        {
            boolean resolved = false;
            // Try preferred contract first
            if (contractPreferred != null)
            {
                for (ContractResourceLoader loader : getResourceHandlerSupport().getContractResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveResourceMeta(loader, resourceName, libraryName, 
                        localePrefix, contractPreferred);
                    if (resourceMeta != null)
                    {
                        resource = new ResourceImpl(resourceMeta, loader, 
                            getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putResource(resourceName, libraryName, contentType,
                                localePrefix, contractPreferred, resourceMeta, loader, 
                                new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));
                        resolved = true;
                        break;
                    }
                }
            }
            if (!resolved && !contracts.isEmpty())
            {
                for (ContractResourceLoader loader : 
                    getResourceHandlerSupport().getContractResourceLoaders())
                {
                    for (String contract : contracts)
                    {
                        ResourceMeta resourceMeta = deriveResourceMeta(
                            loader, resourceName, libraryName, 
                            localePrefix, contract);
                        if (resourceMeta != null)
                        {
                            resource = new ResourceImpl(resourceMeta, loader, 
                                getResourceHandlerSupport(), contentType);

                            // cache it
                            getResourceHandlerCache().putResource(
                                    resourceName, libraryName, contentType,
                                    localePrefix, contract, resourceMeta, loader,
                                    new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));
                            resolved = true;
                            break;
                        }
                    }
                }
            }
            if (!resolved)
            {
                for (ResourceLoader loader : getResourceHandlerSupport().getResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveResourceMeta(
                        loader, resourceName, libraryName, localePrefix);

                    if (resourceMeta != null)
                    {
                        resource = new ResourceImpl(
                            resourceMeta, loader, getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putResource(resourceName, libraryName, contentType,
                                localePrefix, null, resourceMeta, loader, 
                                new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));
                        break;
                    }
                }
            }
        }
        return resource;
    }

    protected ResourceMeta deriveResourceMeta(ContractResourceLoader resourceLoader,
            String resourceName, String libraryName, String localePrefix, String contractName)
    {
        String resourceVersion = null;
        String libraryVersion = null;
        ResourceMeta resourceId = null;
        
        //1. Try to locate resource in a localized path
        if (localePrefix != null)
        {
            if (null != libraryName)
            {
                String pathToLib = localePrefix + '/' + libraryName;
                libraryVersion = resourceLoader.getLibraryVersion(pathToLib, contractName);

                if (null != libraryVersion)
                {
                    String pathToResource = localePrefix + '/'
                            + libraryName + '/' + libraryVersion + '/'
                            + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource, contractName);
                }
                else
                {
                    String pathToResource = localePrefix + '/' + libraryName + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource, contractName);
                }

                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {
                    resourceId = resourceLoader.createResourceMeta(localePrefix, libraryName,
                            libraryVersion, resourceName, resourceVersion, contractName);
                }
            }
            else
            {
                resourceVersion = resourceLoader.getResourceVersion(localePrefix + '/'+ resourceName, contractName);
                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(localePrefix, null, null,
                            resourceName, resourceVersion, contractName);
                }
            }

            if (resourceId != null && !resourceLoader.resourceExists(resourceId))
            {
                resourceId = null;
            }            
        }
        
        //2. Try to localize resource in a non localized path
        if (resourceId == null)
        {
            if (null != libraryName)
            {
                libraryVersion = resourceLoader.getLibraryVersion(libraryName, contractName);

                if (null != libraryVersion)
                {
                    String pathToResource = libraryName + '/' + libraryVersion + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource, contractName);
                }
                else
                {
                    String pathToResource = libraryName + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource, contractName);
                }

                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(null, libraryName,
                            libraryVersion, resourceName, resourceVersion, contractName);
                }
            }
            else
            {
                resourceVersion = resourceLoader.getResourceVersion(resourceName, contractName);
                
                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(null, null, null,
                            resourceName, resourceVersion, contractName);
                }
            }

            if (resourceId != null && !resourceLoader.resourceExists(resourceId))
            {
                resourceId = null;
            }            
        }
        
        return resourceId;
    }
    
    /**
     * This method try to create a ResourceMeta for a specific resource
     * loader. If no library, or resource is found, just return null,
     * so the algorithm in createResource can continue checking with the 
     * next registered ResourceLoader. 
     */
    protected ResourceMeta deriveResourceMeta(ResourceLoader resourceLoader,
            String resourceName, String libraryName, String localePrefix)
    {
        String resourceVersion = null;
        String libraryVersion = null;
        ResourceMeta resourceId = null;
        
        //1. Try to locate resource in a localized path
        if (localePrefix != null)
        {
            if (null != libraryName)
            {
                String pathToLib = localePrefix + '/' + libraryName;
                libraryVersion = resourceLoader.getLibraryVersion(pathToLib);

                if (null != libraryVersion)
                {
                    String pathToResource = localePrefix + '/'
                            + libraryName + '/' + libraryVersion + '/'
                            + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource);
                }
                else
                {
                    String pathToResource = localePrefix + '/' + libraryName + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource);
                }

                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {
                    resourceId = resourceLoader.createResourceMeta(localePrefix, libraryName,
                            libraryVersion, resourceName, resourceVersion);
                }
            }
            else
            {
                resourceVersion = resourceLoader.getResourceVersion(localePrefix + '/'+ resourceName);
                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(localePrefix, null, null,
                            resourceName, resourceVersion);
                }
            }

            if (resourceId != null && !resourceLoader.resourceExists(resourceId))
            {
                resourceId = null;
            }            
        }
        
        //2. Try to localize resource in a non localized path
        if (resourceId == null)
        {
            if (null != libraryName)
            {
                libraryVersion = resourceLoader.getLibraryVersion(libraryName);

                if (null != libraryVersion)
                {
                    String pathToResource = libraryName + '/' + libraryVersion + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource);
                }
                else
                {
                    String pathToResource = libraryName + '/' + resourceName;
                    resourceVersion = resourceLoader.getResourceVersion(pathToResource);
                }

                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(null, libraryName,
                            libraryVersion, resourceName, resourceVersion);
                }
            }
            else
            {
                resourceVersion = resourceLoader.getResourceVersion(resourceName);
                
                if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
                {               
                    resourceId = resourceLoader.createResourceMeta(null, null, null,
                            resourceName, resourceVersion);
                }
            }

            if (resourceId != null && !resourceLoader.resourceExists(resourceId))
            {
                resourceId = null;
            }            
        }
        
        return resourceId;
    }

    @Override
    public String getRendererTypeForResourceName(String resourceName)
    {
        if (resourceName.endsWith(".js"))
        {
            return ResourceUtils.DEFAULT_SCRIPT_RENDERER_TYPE;
        }
        else if (resourceName.endsWith(".css"))
        {
            return ResourceUtils.DEFAULT_STYLESHEET_RENDERER_TYPE;
        }
        return null;
    }

    /**
     *  Handle the resource request, writing in the output. 
     *  
     *  This method implements an algorithm semantically identical to 
     *  the one described on the javadoc of ResourceHandler.handleResourceRequest 
     */
    @Override
    public void handleResourceRequest(FacesContext facesContext) throws IOException
    {
        String resourceBasePath = getResourceHandlerSupport().calculateResourceBasePath(facesContext);

        if (resourceBasePath == null)
        {
            // No base name could be calculated, so no further
            //advance could be done here. HttpServletResponse.SC_NOT_FOUND
            //cannot be returned since we cannot extract the 
            //resource base name
            return;
        }

        // We neet to get an instance of HttpServletResponse, but sometimes
        // the response object is wrapped by several instances of 
        // ServletResponseWrapper (like ResponseSwitch).
        // Since we are handling a resource, we can expect to get an 
        // HttpServletResponse.
        ExternalContext extContext = facesContext.getExternalContext();
        Object response = extContext.getResponse();
        HttpServletResponse httpServletResponse = ExternalContextUtils.getHttpServletResponse(response);
        if (httpServletResponse == null)
        {
            throw new IllegalStateException("Could not obtain an instance of HttpServletResponse.");
        }

        if (isResourceIdentifierExcluded(facesContext, resourceBasePath))
        {
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String resourceName = null;
        if (resourceBasePath.startsWith(ResourceHandler.RESOURCE_IDENTIFIER))
        {
            resourceName = resourceBasePath
                    .substring(ResourceHandler.RESOURCE_IDENTIFIER.length() + 1);

            if (resourceBasePath != null && !ResourceValidationUtils.isValidResourceName(resourceName))
            {
                httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }
        else
        {
            //Does not have the conditions for be a resource call
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String libraryName = facesContext.getExternalContext().getRequestParameterMap().get("ln");

        if (libraryName != null && !ResourceValidationUtils.isValidLibraryName(
                libraryName, isAllowSlashesLibraryName()))
        {
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Resource resource = null;
        if (libraryName != null)
        {
            resource = facesContext.getApplication().getResourceHandler().createResource(resourceName, libraryName);
        }
        else
        {
            resource = facesContext.getApplication().getResourceHandler().createResource(resourceName);
        }

        if (resource == null)
        {
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (!resource.userAgentNeedsUpdate(facesContext))
        {
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        httpServletResponse.setContentType(_getContentType(resource, facesContext.getExternalContext()));

        Map<String, String> headers = resource.getResponseHeaders();

        for (Map.Entry<String, String> entry : headers.entrySet())
        {
            httpServletResponse.setHeader(entry.getKey(), entry.getValue());
        }

        // Sets the preferred buffer size for the body of the response
        extContext.setResponseBufferSize(this.getResourceBufferSize());

        //serve up the bytes (taken from trinidad ResourceServlet)
        try
        {
            InputStream in = resource.getInputStream();
            OutputStream out = httpServletResponse.getOutputStream();
            byte[] buffer = new byte[this.getResourceBufferSize()];

            try
            {
                int count = pipeBytes(in, out, buffer);
                //set the content length
                if (!httpServletResponse.isCommitted())
                {
                    httpServletResponse.setContentLength(count);
                }
            }
            finally
            {
                try
                {
                    in.close();
                }
                finally
                {
                    out.close();
                }
            }
        }
        catch (Exception e)
        {
            if (isConnectionAbort(e))
            {
                if (log.isLoggable(Level.FINE))
                {
                    log.log(Level.FINE, "Connection was aborted while loading resource " + resourceName
                            + " with library " + libraryName);
                }
            }
            else
            {
                if (log.isLoggable(Level.WARNING))
                {
                    log.log(Level.WARNING,"Error trying to load and send resource " + resourceName
                            + " with library " + libraryName + ": "
                            + e.getMessage(), e);
                }
                httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }

    private static boolean isConnectionAbort(Exception e)
    {
        String exceptionName = e.getClass().getCanonicalName();

        // Tomcat
        if ("org.apache.catalina.connector.ClientAbortException".equals(exceptionName))
        {
            return true;
        }

        // Jetty
        if ("org.eclipse.jetty.io.EofException".equals(exceptionName))
        {
            return true;
        }

        // Undertow / Quarkus
        if (e instanceof IOException
                && e.getCause() instanceof IllegalStateException
                && e.getCause().getMessage().contains("UT000127"))
        {
            return true;
        }

        return false;
    }

    /**
     * Reads the specified input stream into the provided byte array storage and
     * writes it to the output stream.
     */
    private static int pipeBytes(InputStream in, OutputStream out, byte[] buffer) throws IOException
    {
        int count = 0;
        int length;

        while ((length = (in.read(buffer))) >= 0)
        {
            out.write(buffer, 0, length);
            count += length;
        }
        return count;
    }

    @Override
    public boolean isResourceRequest(FacesContext facesContext)
    {
        // Since this method could be called many times we save it
        // on request map so the first time is calculated it remains
        // alive until the end of the request
        Boolean value = (Boolean) facesContext.getAttributes().get(IS_RESOURCE_REQUEST);

        if (value == null)
        {
            String resourceBasePath = getResourceHandlerSupport()
                    .calculateResourceBasePath(facesContext);

            value = resourceBasePath != null
                    && resourceBasePath.startsWith(ResourceHandler.RESOURCE_IDENTIFIER);
            facesContext.getAttributes().put(IS_RESOURCE_REQUEST, value);
        }
        return value;
    }

    protected String getLocalePrefixForLocateResource()
    {
        return getLocalePrefixForLocateResource(FacesContext.getCurrentInstance());
    }

    protected String getLocalePrefixForLocateResource(FacesContext context)
    {
        String localePrefix = null;
        boolean isResourceRequest = context.getApplication().getResourceHandler().isResourceRequest(context);

        if (isResourceRequest)
        {
            localePrefix = context.getExternalContext().getRequestParameterMap().get("loc");
            
            if (localePrefix != null)
            {
                if (!ResourceValidationUtils.isValidLocalePrefix(localePrefix))
                {
                    return null;
                }
                return localePrefix;
            }
        }
        
        String bundleName = context.getApplication().getMessageBundle();
        if (bundleName != null)
        {
            Locale locale = null;
            
            if (isResourceRequest || context.getViewRoot() == null)
            {
                locale = context.getApplication().getViewHandler().calculateLocale(context);
            }
            else
            {
                locale = context.getViewRoot().getLocale();
            }

            try
            {
                ResourceBundle bundle;
                ResourceBundle.Control bundleControl = MyfacesConfig.getCurrentInstance(context)
                        .getResourceBundleControl();
                if (bundleControl == null)
                {
                    bundle = ResourceBundle.getBundle(bundleName, locale, ClassUtils.getContextClassLoader());
                }
                else
                {
                    bundle = ResourceBundle.getBundle(bundleName, locale, ClassUtils.getContextClassLoader(),
                            bundleControl);
                }

                if (bundle != null && bundle.containsKey(ResourceHandler.LOCALE_PREFIX))
                {
                    localePrefix = bundle.getString(ResourceHandler.LOCALE_PREFIX);
                }
            }
            catch (MissingResourceException e)
            {
                // Ignore it and return null
            }
        }

        return localePrefix;
    }
    
    protected String getContractNameForLocateResource(FacesContext context)
    {
        String contractName = null;
        boolean isResourceRequest = context.getApplication().getResourceHandler().isResourceRequest(context);

        if (isResourceRequest)
        {
            contractName = context.getExternalContext().getRequestParameterMap().get("con");
        }
        
        // Check if the contract has been injected.
        if (contractName == null)
        {
            contractName = (String) context.getAttributes().get(ContractResource.CONTRACT_SELECTED);
        }
        
        //Validate
        if (contractName != null &&
            !ResourceValidationUtils.isValidContractName(contractName))
        {
            return null;
        }
        return contractName;
    }

    protected boolean isResourceIdentifierExcluded(FacesContext context, String resourceIdentifier)
    {
        if (_excludedResourceExtensions == null)
        {
            String value = WebConfigParamUtils.getStringInitParameter(context.getExternalContext(),
                            RESOURCE_EXCLUDES_PARAM_NAME,
                            RESOURCE_EXCLUDES_DEFAULT_VALUE);
            
            _excludedResourceExtensions = StringUtils.splitShortString(value, ' ');
        }
        
        for (int i = 0; i < _excludedResourceExtensions.length; i++)
        {
            if (resourceIdentifier.endsWith(_excludedResourceExtensions[i]))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a library exists or not. This is done delegating
     * to each ResourceLoader used, because each one has a different
     * prefix and way to load resources.
     * 
     */
    @Override
    public boolean libraryExists(String libraryName)
    {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        String localePrefix = getLocalePrefixForLocateResource(facesContext);
        final List<String> contracts = facesContext.getResourceLibraryContracts(); 

        String pathToLib = null;
        Boolean libraryFound = null;
        if (libraryName != null && !ResourceValidationUtils.isValidLibraryName(
                libraryName, isAllowSlashesLibraryName()))
        {
            return false;
        }
        
        if (localePrefix != null)
        {
            //Check with locale
            pathToLib = localePrefix + '/' + libraryName;

            libraryFound = getResourceHandlerCache().libraryExists(pathToLib);
            if (libraryFound != null)
            {
                return libraryFound;
            }
        }
        libraryFound = getResourceHandlerCache().libraryExists(libraryName);
        if (libraryFound != null)
        {
            return libraryFound;
        }
        
        if (localePrefix != null)
        {
            if (!contracts.isEmpty())
            {
                for (String contract : contracts)
                {
                    for (ContractResourceLoader loader : getResourceHandlerSupport().getContractResourceLoaders())
                    {
                        if (loader.libraryExists(pathToLib, contract))
                        {
                            getResourceHandlerCache().confirmLibraryExists(pathToLib);
                            return true;
                        }
                    }
                }
            }
            
            for (ResourceLoader loader : getResourceHandlerSupport().getResourceLoaders())
            {
                if (loader.libraryExists(pathToLib))
                {
                    getResourceHandlerCache().confirmLibraryExists(pathToLib);
                    return true;
                }
            }            
        }

        //Check without locale
        if (!contracts.isEmpty())
        {
            for (String contract : contracts)
            {
                for (ContractResourceLoader loader : getResourceHandlerSupport().getContractResourceLoaders())
                {
                    if (loader.libraryExists(libraryName, contract))
                    {
                        getResourceHandlerCache().confirmLibraryExists(libraryName);
                        return true;
                    }
                }
            }
        }

        for (ResourceLoader loader : getResourceHandlerSupport().getResourceLoaders())
        {
            if (loader.libraryExists(libraryName))
            {
                getResourceHandlerCache().confirmLibraryExists(libraryName);
                return true;
            }
        }

        if (localePrefix != null)
        {
            //Check with locale
            getResourceHandlerCache().confirmLibraryNotExists(pathToLib);
        }
        else
        {
            getResourceHandlerCache().confirmLibraryNotExists(libraryName);
        }
        return false;
    }

    public void setResourceHandlerSupport(ResourceHandlerSupport resourceHandlerSupport)
    {
        _resourceHandlerSupport = resourceHandlerSupport;
    }

    protected ResourceHandlerSupport getResourceHandlerSupport()
    {
        if (_resourceHandlerSupport == null)
        {
            _resourceHandlerSupport = new DefaultResourceHandlerSupport();
        }
        return _resourceHandlerSupport;
    }

    protected ResourceHandlerCache getResourceHandlerCache()
    {
        if (_resourceHandlerCache == null)
        {
            _resourceHandlerCache = new ResourceHandlerCache();
        }
        return _resourceHandlerCache;
    }

    protected String _getContentType(Resource resource, ExternalContext externalContext)
    {
        String contentType = resource.getContentType();

        // the resource does not provide a content-type --> determine it via mime-type
        if (contentType == null || contentType.length() == 0)
        {
            String resourceName = getWrappedResourceName(resource);

            if (resourceName != null)
            {
                contentType = externalContext.getMimeType(resourceName);
            }
        }

        return contentType;
    }

    /**
     * Recursively unwarp the resource until we find the real resourceName
     * This is needed because the Faces2 specced ResourceWrapper doesn't override
     * the getResourceName() method :(
     * @param resource
     * @return the first non-null resourceName or <code>null</code> if none set
     */
    private String getWrappedResourceName(Resource resource)
    {
        String resourceName = resource.getResourceName();
        if (resourceName != null)
        {
            return resourceName;
        }

        if (resource instanceof ResourceWrapper wrapper)
        {
            return getWrappedResourceName(wrapper.getWrapped());
        }

        return null;
    }
    
    protected boolean isAllowSlashesLibraryName()
    {
        if (_allowSlashLibraryName == null)
        {
            _allowSlashLibraryName = MyfacesConfig.getCurrentInstance().isStrictJsf2AllowSlashLibraryName();
        }
        return _allowSlashLibraryName;
    }

    protected int getResourceBufferSize()
    {
        if (_resourceBufferSize == -1)
        {
            _resourceBufferSize = MyfacesConfig.getCurrentInstance().getResourceBufferSize();
        }
        return _resourceBufferSize;
    }

    @Override
    public Resource createResourceFromId(String resourceId)
    {
        Resource resource = null;

        Assert.notNull(resourceId, "resourceId");
        
        // Later in deriveResourceMeta the resourceId is decomposed and
        // its elements validated properly.
        if (!ResourceValidationUtils.isValidResourceId(resourceId))
        {
            return null;
        }
        
        FacesContext facesContext = FacesContext.getCurrentInstance();
        final List<String> contracts = facesContext.getResourceLibraryContracts(); 
        String contractPreferred = getContractNameForLocateResource(facesContext);
        ResourceValue resourceValue = null;
        
        // Check cache:
        //
        // Contracts are on top of everything, because it is a concept that defines
        // resources in a application scope concept. It means all resources in
        // /resources or /META-INF/resources can be overridden using a contract. Note
        // it also means resources under /META-INF/flows can also be overridden using
        // a contract.
        if (contractPreferred != null)
        {
            resourceValue = getResourceHandlerCache().getResource(resourceId, contractPreferred);
        }
        if (resourceValue == null && !contracts.isEmpty())
        {
            // Try to get resource but try with a contract name
            for (String contract : contracts)
            {
                resourceValue = getResourceHandlerCache().getResource(resourceId, contract);
                if (resourceValue != null)
                {
                    break;
                }
            }
        }
        if (resourceValue == null)
        {
            // Try to get resource without contract name
            resourceValue = getResourceHandlerCache().getResource(resourceId);
        }
        
        if (resourceValue != null)
        {        
            //Resolve contentType using ExternalContext.getMimeType
            String contentType = facesContext.getExternalContext().getMimeType(
                resourceValue.getResourceMeta().getResourceName());

            resource = new ResourceImpl(resourceValue.getResourceMeta(), resourceValue.getResourceLoader(),
                    getResourceHandlerSupport(), contentType,
                    resourceValue.getCachedInfo() != null ? resourceValue.getCachedInfo().getURL() : null, 
                    resourceValue.getCachedInfo() != null ? resourceValue.getCachedInfo().getRequestPath() : null);
        }
        else
        {
            boolean resolved = false;
            if (contractPreferred != null)
            {
                for (ContractResourceLoader loader : getResourceHandlerSupport().getContractResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveResourceMeta(
                        facesContext, loader, resourceId, contractPreferred);
                    if (resourceMeta != null)
                    {
                        String contentType = facesContext.getExternalContext().getMimeType(
                            resourceMeta.getResourceName());
                        
                        resource = new ResourceImpl(resourceMeta, loader, 
                            getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putResource(resourceId, resourceMeta, loader, 
                            new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));
                        
                        resolved = true;
                        break;
                    }
                }
            }
            if (!resolved && !contracts.isEmpty())
            {
                for (ContractResourceLoader loader : 
                        getResourceHandlerSupport().getContractResourceLoaders())
                {
                    for (String contract : contracts)
                    {
                        ResourceMeta resourceMeta = deriveResourceMeta(
                            facesContext, loader, resourceId, contract);
                        if (resourceMeta != null)
                        {
                            String contentType = facesContext.getExternalContext().getMimeType(
                                resourceMeta.getResourceName());

                            resource = new ResourceImpl(resourceMeta, loader, 
                                getResourceHandlerSupport(), contentType);

                            // cache it
                            getResourceHandlerCache().putResource(resourceId, resourceMeta, loader, 
                                new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));

                            resolved = true;
                            break;
                        }
                    }
                }
            }
            if (!resolved)
            {
                for (ResourceLoader loader : getResourceHandlerSupport().getResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveResourceMeta(facesContext, loader, resourceId);

                    if (resourceMeta != null)
                    {
                        String contentType = facesContext.getExternalContext().getMimeType(
                            resourceMeta.getResourceName());

                        resource = new ResourceImpl(resourceMeta, loader, getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putResource(resourceId, resourceMeta, loader, 
                            new ResourceCachedInfo(resource.getURL(), resource.getRequestPath()));
                        break;
                    }
                }
            }
        }
        return resource;
    }

    protected ResourceMeta deriveResourceMeta(FacesContext context, ResourceLoader resourceLoader,
            String resourceId)
    {
        ResourceMeta resourceMeta = null;
        String token = null;
        String localePrefix = null;
        String libraryName = null;
        String libraryVersion = null;
        String resourceName = null;
        String resourceVersion = null;

        int lastSlash = resourceId.lastIndexOf('/');
        if (lastSlash < 0)
        {
            //no slashes, so it is just a plain resource.
            resourceName = resourceId;
        }
        else
        {
            token = resourceId.substring(lastSlash+1);
            if (RESOURCE_VERSION_CHECKER.matcher(token).matches())
            {
                int secondLastSlash = resourceId.lastIndexOf('/', lastSlash-1);
                if (secondLastSlash < 0)
                {
                    secondLastSlash = 0;
                }

                String rnToken = resourceId.substring(secondLastSlash+1, lastSlash);
                int lastPoint = rnToken.lastIndexOf('.');
                // lastPoint < 0 means it does not match, the token is not a resource version
                if (lastPoint >= 0)
                {
                    String ext = rnToken.substring(lastPoint);
                    if (token.endsWith(ext))
                    {
                        //It match a versioned resource
                        resourceVersion = token.substring(0,token.length()-ext.length());
                    }
                }
            }

            // 1. Extract the library path and locale prefix if necessary
            int start = 0;
            int firstSlash = resourceId.indexOf('/');

            // At least one slash, check if the start is locale prefix.
            String bundleName = context.getApplication().getMessageBundle();
            //If no bundle set, it can't be localePrefix
            if (null != bundleName)
            {
                token = resourceId.substring(start, firstSlash);
                //Try to derive a locale object
                Locale locale = LocaleUtils.deriveLocale(token);

                // If the locale was derived and it is available, 
                // assume that portion of the resourceId it as a locale prefix.
                if (locale != null && LocaleUtils.isAvailableLocale(locale))
                {
                    localePrefix = token;
                    start = firstSlash+1;
                }
            }

            //Check slash again from start
            firstSlash = resourceId.indexOf('/', start);
            if (firstSlash < 0)
            {
                //no slashes.
                resourceName = resourceId.substring(start);
            }
            else
            {
                //check libraryName
                token = resourceId.substring(start, firstSlash);
                int minResourceNameSlash = (resourceVersion != null) ?
                    resourceId.lastIndexOf('/', lastSlash-1) : lastSlash;

                if (start < minResourceNameSlash)
                {
                    libraryName = token;
                    start = firstSlash+1;

                    //Now that libraryName exists, check libraryVersion
                    firstSlash = resourceId.indexOf('/', start);
                    if (firstSlash >= 0)
                    {
                        token = resourceId.substring(start, firstSlash);
                        if (LIBRARY_VERSION_CHECKER.matcher(token).matches())
                        {
                            libraryVersion = token;
                            start = firstSlash+1;
                        }
                    }
                }

                firstSlash = resourceId.indexOf('/', start);
                if (firstSlash < 0)
                {
                    //no slashes.
                    resourceName = resourceId.substring(start);
                }
                else
                {
                    // Check resource version. 
                    if (resourceVersion != null)
                    {
                        resourceName = resourceId.substring(start,lastSlash);
                    }
                    else
                    {
                        //no resource version, assume the remaining to be resource name
                        resourceName = resourceId.substring(start);
                    }
                }
            }
        }

        //Check libraryName and resourceName
        if (resourceName == null)
        {
            return null;
        }
        if (!ResourceValidationUtils.isValidResourceName(resourceName))
        {
            return null;
        }

        if (libraryName != null && !ResourceValidationUtils.isValidLibraryName(
                libraryName, isAllowSlashesLibraryName()))
        {
            return null;
        }

        // If some variable is "" set it as null.
        if (localePrefix != null && localePrefix.length() == 0)
        {
            localePrefix = null;
        }
        if (libraryName != null && libraryName.length() == 0)
        {
            libraryName = null;
        }
        if (libraryVersion != null && libraryVersion.length() == 0)
        {
            libraryVersion = null;
        }
        if (resourceName != null && resourceName.length() == 0)
        {
            resourceName = null;
        }
        if (resourceVersion != null && resourceVersion.length() == 0)
        {
            resourceVersion = null;
        }

        resourceMeta = resourceLoader.createResourceMeta(
            localePrefix, libraryName, libraryVersion, resourceName, resourceVersion);

        if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
        {
            resourceMeta = null;
        }

        return resourceMeta;
    }
    
    protected ResourceMeta deriveResourceMeta(FacesContext context, ContractResourceLoader resourceLoader,
            String resourceId, String contractName)
    {
        ResourceMeta resourceMeta = null;
        String token = null;
        String localePrefix = null;
        String libraryName = null;
        String libraryVersion = null;
        String resourceName = null;
        String resourceVersion = null;

        int lastSlash = resourceId.lastIndexOf('/');
        if (lastSlash < 0)
        {
            //no slashes, so it is just a plain resource.
            resourceName = resourceId;
        }
        else
        {
            token = resourceId.substring(lastSlash+1);
            if (RESOURCE_VERSION_CHECKER.matcher(token).matches())
            {
                int secondLastSlash = resourceId.lastIndexOf('/', lastSlash-1);
                if (secondLastSlash < 0)
                {
                    secondLastSlash = 0;
                }

                String rnToken = resourceId.substring(secondLastSlash+1, lastSlash);
                int lastPoint = rnToken.lastIndexOf('.');
                // lastPoint < 0 means it does not match, the token is not a resource version
                if (lastPoint >= 0)
                {
                    String ext = rnToken.substring(lastPoint);
                    if (token.endsWith(ext))
                    {
                        //It match a versioned resource
                        resourceVersion = token.substring(0,token.length()-ext.length());
                    }
                }
            }

            // 1. Extract the library path and locale prefix if necessary
            int start = 0;
            int firstSlash = resourceId.indexOf('/');

            // At least one slash, check if the start is locale prefix.
            String bundleName = context.getApplication().getMessageBundle();
            //If no bundle set, it can't be localePrefix
            if (null != bundleName)
            {
                token = resourceId.substring(start, firstSlash);
                //Try to derive a locale object
                Locale locale = LocaleUtils.deriveLocale(token);

                // If the locale was derived and it is available, 
                // assume that portion of the resourceId it as a locale prefix.
                if (locale != null && LocaleUtils.isAvailableLocale(locale))
                {
                    localePrefix = token;
                    start = firstSlash+1;
                }
            }

            //Check slash again from start
            firstSlash = resourceId.indexOf('/', start);
            if (firstSlash < 0)
            {
                //no slashes.
                resourceName = resourceId.substring(start);
            }
            else
            {
                //check libraryName
                token = resourceId.substring(start, firstSlash);
                int minResourceNameSlash = (resourceVersion != null) ?
                    resourceId.lastIndexOf('/', lastSlash-1) : lastSlash;
                if (start < minResourceNameSlash)
                {
                    libraryName = token;
                    start = firstSlash+1;

                    //Now that libraryName exists, check libraryVersion
                    firstSlash = resourceId.indexOf('/', start);
                    if (firstSlash >= 0)
                    {
                        token = resourceId.substring(start, firstSlash);
                        if (LIBRARY_VERSION_CHECKER.matcher(token).matches())
                        {
                            libraryVersion = token;
                            start = firstSlash+1;
                        }
                    }
                }

                firstSlash = resourceId.indexOf('/', start);
                if (firstSlash < 0)
                {
                    //no slashes.
                    resourceName = resourceId.substring(start);
                }
                else
                {
                    // Check resource version. 
                    if (resourceVersion != null)
                    {
                        resourceName = resourceId.substring(start,lastSlash);
                    }
                    else
                    {
                        //no resource version, assume the remaining to be resource name
                        resourceName = resourceId.substring(start);
                    }
                }
            }
        }

        //Check libraryName and resourceName
        if (resourceName == null)
        {
            return null;
        }
        if (!ResourceValidationUtils.isValidResourceName(resourceName))
        {
            return null;
        }

        if (libraryName != null
                && !ResourceValidationUtils.isValidLibraryName(libraryName, isAllowSlashesLibraryName()))
        {
            return null;
        }

        // If some variable is "" set it as null.
        if (localePrefix != null && localePrefix.length() == 0)
        {
            localePrefix = null;
        }
        if (libraryName != null && libraryName.length() == 0)
        {
            libraryName = null;
        }
        if (libraryVersion != null && libraryVersion.length() == 0)
        {
            libraryVersion = null;
        }
        if (resourceName != null && resourceName.length() == 0)
        {
            resourceName = null;
        }
        if (resourceVersion != null && resourceVersion.length() == 0)
        {
            resourceVersion = null;
        }

        resourceMeta = resourceLoader.createResourceMeta(
            localePrefix, libraryName, libraryVersion, resourceName, resourceVersion, contractName);

        if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
        {
            resourceMeta = null;
        }

        return resourceMeta;
    }
    
    protected ResourceMeta deriveViewResourceMeta(FacesContext context, ResourceLoader resourceLoader,
            String resourceName, String localePrefix)
    {
        ResourceMeta resourceMeta = null;
        String resourceVersion = null;

        //1. Try to locate resource in a localized path
        if (localePrefix != null)
        {
            resourceVersion = resourceLoader
                    .getResourceVersion(localePrefix + '/'+ resourceName);
            if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
            {
                resourceMeta = resourceLoader.createResourceMeta(localePrefix, null, null,
                         resourceName, resourceVersion);
            }

            if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
            {
                resourceMeta = null;
            }            
        }
        
        //2. Try to localize resource in a non localized path
        if (resourceMeta == null)
        {
            resourceVersion = resourceLoader.getResourceVersion(resourceName);
            if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
            {
                resourceMeta = resourceLoader.createResourceMeta(null, null, null, resourceName, resourceVersion);
            }

            if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
            {
                resourceMeta = null;
            }            
        }

        return resourceMeta;        
    }
    
    protected ResourceMeta deriveViewResourceMeta(FacesContext context, ContractResourceLoader resourceLoader,
            String resourceName, String localePrefix, String contractName)
    {
        ResourceMeta resourceMeta = null;
        String resourceVersion = null;

        //1. Try to locate resource in a localized path
        if (localePrefix != null)
        {
            resourceVersion = resourceLoader
                    .getResourceVersion(localePrefix + '/'+ resourceName, contractName);
            if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
            {
                resourceMeta = resourceLoader.createResourceMeta(localePrefix, null, null,
                     resourceName, resourceVersion, contractName);
            }

            if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
            {
                resourceMeta = null;
            }            
        }
        
        //2. Try to localize resource in a non localized path
        if (resourceMeta == null)
        {
            resourceVersion = resourceLoader.getResourceVersion(resourceName, contractName);
            if (!(resourceVersion != null && ResourceLoader.VERSION_INVALID.equals(resourceVersion)))
            {
                resourceMeta = resourceLoader.createResourceMeta(null, null, null,
                         resourceName, resourceVersion, contractName);
            }

            if (resourceMeta != null && !resourceLoader.resourceExists(resourceMeta))
            {
                resourceMeta = null;
            }            
        }

        return resourceMeta;
    }

    @Override
    public Resource createViewResource(FacesContext facesContext, String resourceName)
    {
        // There are some special points to remember for a view resource in comparison
        // with a normal resource:
        //
        // - A view resource never has an associated library name 
        //   (this was done to keep simplicity).
        // - A view resource can be inside a resource library contract.
        // - A view resource could be internationalized in the same way a normal resource.
        // - A view resource can be created from the webapp root folder, 
        //   a normal resource cannot.
        // - A view resource cannot be created from /resources or META-INF/resources.
        // 
        // For example, a valid resourceId for a view resource is like this:
        //
        // [localePrefix/]resourceName[/resourceVersion]
        //
        // but the resource loader can ignore localePrefix or resourceVersion, like
        // for example the webapp root folder.
        // 
        // When createViewResource() is called, the view must be used to derive
        // the localePrefix and facesContext must be used to get the available contracts.
        
        Resource resource = null;

        Assert.notNull(resourceName, "resourceName");
        
        if (resourceName.charAt(0) == '/')
        {
            // If resourceName starts with '/', remove that character because it
            // does not have any meaning (with and without should point to the 
            // same resource).
            resourceName = resourceName.substring(1);
        }
        
        // Later in deriveResourceMeta the resourceId is decomposed and
        // its elements validated properly.
        if (!ResourceValidationUtils.isValidViewResource(resourceName))
        {
            return null;
        }
        final String localePrefix = getLocalePrefixForLocateResource(facesContext);
        String contentType = facesContext.getExternalContext().getMimeType(resourceName);
        final List<String> contracts = facesContext.getResourceLibraryContracts(); 
        String contractPreferred = getContractNameForLocateResource(facesContext);
        ResourceValue resourceValue = null;
        
        // Check cache:
        //
        // Contracts are on top of everything, because it is a concept that defines
        // resources in a application scope concept. It means all resources in
        // /resources or /META-INF/resources can be overridden using a contract. Note
        // it also means resources under /META-INF/flows can also be overridden using
        // a contract.
        if (contractPreferred != null)
        {
            resourceValue = getResourceHandlerCache().getViewResource(
                    resourceName, contentType, localePrefix, contractPreferred);
        }
        if (resourceValue == null && !contracts.isEmpty())
        {
            // Try to get resource but try with a contract name
            for (String contract : contracts)
            {
                resourceValue = getResourceHandlerCache().getViewResource(
                    resourceName, contentType, localePrefix, contract);
                if (resourceValue != null)
                {
                    break;
                }
            }
        }
        if (resourceValue == null)
        {
            // Try to get resource without contract name
            resourceValue = getResourceHandlerCache().getViewResource(resourceName, contentType, localePrefix);
        }

        if(resourceValue != null)
        {        
            resource = new ResourceImpl(resourceValue.getResourceMeta(), resourceValue.getResourceLoader(),
                    getResourceHandlerSupport(), contentType, 
                    resourceValue.getCachedInfo() != null ? resourceValue.getCachedInfo().getURL() : null, null);
        }
        else
        {
            boolean resolved = false;
            if (contractPreferred != null)
            {
                for (ContractResourceLoader loader : getResourceHandlerSupport().getContractResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveViewResourceMeta(
                        facesContext, loader, resourceName, localePrefix, contractPreferred);
                    if (resourceMeta != null)
                    {
                        resource = new ResourceImpl(resourceMeta, loader, getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putViewResource(
                            resourceName, contentType, localePrefix, contractPreferred, resourceMeta, loader, 
                            new ResourceCachedInfo(resource.getURL(), null));
                        
                        resolved = true;
                        break;
                    }
                }
            }
            if (!resolved && !contracts.isEmpty())
            {
                for (ContractResourceLoader loader : 
                        getResourceHandlerSupport().getContractResourceLoaders())
                {
                    for (String contract : contracts)
                    {
                        ResourceMeta resourceMeta = deriveViewResourceMeta(
                            facesContext, loader, resourceName, localePrefix, contract);
                        if (resourceMeta != null)
                        {
                            resource = new ResourceImpl(resourceMeta, loader,
                                    getResourceHandlerSupport(), contentType);

                            // cache it
                            getResourceHandlerCache().putViewResource(
                                resourceName, contentType, localePrefix, contract, resourceMeta, loader,
                                new ResourceCachedInfo(resource.getURL(), null));

                            resolved = true;
                            break;
                        }
                    }
                }
            }
            if (!resolved)
            {
                // "... Considering the web app root ..."
                
                // "... Considering faces flows (at the locations specified in the spec prose document section 
                // Faces Flows in the Using Faces in Web Applications chapter) ..."
                for (ResourceLoader loader : getResourceHandlerSupport().getViewResourceLoaders())
                {
                    ResourceMeta resourceMeta = deriveViewResourceMeta(
                        facesContext, loader, resourceName, localePrefix);

                    if (resourceMeta != null)
                    {
                        resource = new ResourceImpl(resourceMeta, loader, getResourceHandlerSupport(), contentType);

                        // cache it
                        getResourceHandlerCache().putViewResource(
                            resourceName, contentType, localePrefix, resourceMeta, loader,
                            new ResourceCachedInfo(resource.getURL(), null));
                        break;
                    }
                }
            }
        }
        return resource;
    }

    @Override
    public Stream<String> getViewResources(FacesContext facesContext, 
            String path, int maxDepth, ResourceVisitOption... options)   
    {
        final String localePrefix = getLocalePrefixForLocateResource(facesContext);
        final List<String> contracts = facesContext.getResourceLibraryContracts(); 
        String contractPreferred = getContractNameForLocateResource(facesContext);

        if (this._viewSuffixes == null)
        {
            this._viewSuffixes = loadSuffixes(facesContext.getExternalContext());
        }

        Iterator it = new FilterInvalidSuffixViewResourceIterator(new ViewResourceIterator(facesContext, 
                    getResourceHandlerSupport(), localePrefix, contracts,
                    contractPreferred, path, maxDepth, options), facesContext, _viewSuffixes);

        return Stream.concat(StreamSupport.stream(Spliterators.spliteratorUnknownSize(it,Spliterator.DISTINCT),
                                                    false), getProgrammaticViewIds(facesContext));
    }

    private Stream<String> getProgrammaticViewIds(FacesContext facesContext)
    {
        ArrayList<String> views = new ArrayList<String>();
        BeanManager beanManager = CDIUtils.getBeanManager(facesContext);
        if (beanManager != null)
        {
            for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE))
            {
                for (Annotation qualifier : bean.getQualifiers())
                {
                    if (qualifier instanceof View view)
                    {
                        views.add(view.value());
                    }
                }
            }
        }
        return views.stream();
    }
    
    private Set<String> loadSuffixes(ExternalContext context)
    {
        Set<String> result = new HashSet<>();
        String definedSuffixes = WebConfigParamUtils.getStringInitParameter(context, 
                ViewHandler.FACELETS_SUFFIX_PARAM_NAME, ViewHandler.DEFAULT_FACELETS_SUFFIX);
        StringTokenizer tokenizer;
        
        if (definedSuffixes == null) 
        {
            definedSuffixes = ViewHandler.DEFAULT_FACELETS_SUFFIX;
        }
        
        // This is a space-separated list of suffixes, so parse them out.
        
        tokenizer = new StringTokenizer(definedSuffixes, " ");
        
        while (tokenizer.hasMoreTokens()) 
        {
            result.add(tokenizer.nextToken());
        }
        
        String faceletSuffix = WebConfigParamUtils.getStringInitParameter(context, 
                ViewHandler.FACELETS_SUFFIX_PARAM_NAME, ViewHandler.DEFAULT_FACELETS_SUFFIX);
        
        if (faceletSuffix != null)
        {
            result.add(faceletSuffix.trim());
        }
        
        String faceletViewMappings = WebConfigParamUtils.getStringInitParameter(context, FACELETS_VIEW_MAPPINGS_PARAM);
        
        if (faceletViewMappings != null)
        {
            tokenizer = new StringTokenizer(faceletViewMappings, ";");
            while (tokenizer.hasMoreTokens()) 
            {
                result.add(tokenizer.nextToken());
            }
        }
        
        return result;
    }    
    
    /*
     * Filter out views without a valid suffix.
     */
    private static class FilterInvalidSuffixViewResourceIterator extends SkipMatchIterator<String>
    {
        private FacesContext facesContext;
        private Set<String> validSuffixes;

        public FilterInvalidSuffixViewResourceIterator(Iterator<String> delegate, FacesContext facesContext,
                Set<String> validSuffixes)
        {
            super(delegate);
            this.facesContext = facesContext;
            this.validSuffixes = validSuffixes;
        }

        @Override
        protected boolean match(String value)
        {
            String viewId = value;
            ViewDeclarationLanguage vdl = facesContext.getApplication().getViewHandler()
                    .getViewDeclarationLanguage(facesContext, viewId);
            if (vdl != null && vdl.viewExists(facesContext, viewId))
            {
                boolean matchSuffix = false;
                for (String suffix : validSuffixes)
                {
                    if (suffix != null && suffix.length() > 0 && viewId.endsWith(suffix))
                    {
                        matchSuffix = true;
                        break;
                    }
                }
                if (matchSuffix)
                {
                    //There is view, do not match
                    return false;
                }
                else
                {
                    return true;
                }
            }
            // It is another resource file, skip
            return true;
        }
    }

    /**
     * @since 2.3
     * @param facesContext
     * @param resourceName
     * @param libraryName
     * @return 
     */
    @Override
    public boolean isResourceRendered(FacesContext facesContext, String resourceName, String libraryName)
    {
        return getRenderedResources(facesContext).containsKey(
                libraryName != null
                        ? contactLibraryAndResource(facesContext, libraryName, resourceName)
                        : resourceName);
    }

    /**
     * @since 2.3
     * @param facesContext
     * @param resourceName
     * @param libraryName 
     */
    @Override
    public void markResourceRendered(FacesContext facesContext, String resourceName, String libraryName)
    {
        getRenderedResources(facesContext).put(
                libraryName != null
                        ? contactLibraryAndResource(facesContext, libraryName, resourceName)
                        : resourceName,
                Boolean.TRUE);
    }
    
    /**
     * Return a set of already rendered resources by this renderer on the current
     * request. 
     * 
     * @param facesContext
     * @return
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> getRenderedResources(FacesContext facesContext)
    {
        Map<String, Boolean> map = (Map<String, Boolean>) facesContext.getViewRoot().getTransientStateHelper()
                .getTransient(RENDERED_RESOURCES_SET);
        if (map == null)
        {
            map = new HashMap<>();
            facesContext.getViewRoot().getTransientStateHelper().putTransient(RENDERED_RESOURCES_SET, map);
        }
        return map;
    }

    private static String contactLibraryAndResource(FacesContext facesContext, String libraryName, String resourceName)
    {       
        StringBuilder sb = SharedStringBuilder.get(facesContext, SHARED_STRING_BUILDER, 40);
        return sb.append(libraryName).append('/').append(resourceName).toString();
    }
}
