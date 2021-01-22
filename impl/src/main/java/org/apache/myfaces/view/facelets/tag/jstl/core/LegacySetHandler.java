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

package org.apache.myfaces.view.facelets.tag.jstl.core;

import java.io.IOException;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ValueExpression;
import jakarta.faces.FacesException;
import jakarta.faces.component.UIComponent;
import jakarta.faces.view.facelets.FaceletContext;
import jakarta.faces.view.facelets.FaceletException;
import jakarta.faces.view.facelets.TagAttribute;
import jakarta.faces.view.facelets.TagConfig;
import jakarta.faces.view.facelets.TagException;
import jakarta.faces.view.facelets.TagHandler;
import org.apache.myfaces.view.facelets.AbstractFaceletContext;

/**
 * Simplified implementation of c:set
 * 
 * Sets the result of an expression evaluation in a 'scope'
 * 
 * NOTE: This implementation is provided for compatibility reasons and
 * it is considered faulty. It is enabled using
 * org.apache.myfaces.STRICT_JSF_2_FACELETS_COMPATIBILITY web config param.
 * Don't use it if EL expression caching is enabled.
 * 
 * @author Jacob Hookom
 * @version $Id: SetHandler.java,v 1.2 2008/07/13 19:01:44 rlubke Exp $
 */
//@JSFFaceletTag(name="c:set")
public class LegacySetHandler extends TagHandler
{

    /**
     * Name of the exported scoped variable to hold the value
     * specified in the action. The type of the scoped variable is
     * whatever type the value expression evaluates to.
     */
    private final TagAttribute var;

    /**
     * Expression to be evaluated.
     */
    private final TagAttribute value;

    private final TagAttribute scope;

    private final TagAttribute target;

    private final TagAttribute property;

    public LegacySetHandler(TagConfig config)
    {
        super(config);
        this.value = this.getAttribute("value");
        this.var = this.getAttribute("var");
        this.scope = this.getAttribute("scope");
        this.target = this.getAttribute("target");
        this.property = this.getAttribute("property");
    }

    @Override
    public void apply(FaceletContext ctx, UIComponent parent) throws IOException, FacesException, FaceletException,
            ELException
    {
        ValueExpression veObj = this.value.getValueExpression(ctx, Object.class);

        if (this.var != null)
        {
            // Get variable name
            String varStr = this.var.getValue(ctx);

            if (this.scope != null)
            {
                String scopeStr = this.scope.getValue(ctx);

                // Check scope string
                if (scopeStr == null || scopeStr.length() == 0)
                {
                    throw new TagException(tag, "scope must not be empty");
                }
                if ("page".equals(scopeStr))
                {
                    throw new TagException(tag, "page scope is not allowed");
                }

                // Build value expression string to set variable
                StringBuilder expStr = new StringBuilder().append("#{").append(scopeStr);
                if ("request".equals(scopeStr) || "view".equals(scopeStr) || "session".equals(scopeStr)
                        || "application".equals(scopeStr))
                {
                    expStr.append("Scope");
                }
                expStr.append('.').append(varStr).append('}');
                ELContext elCtx = ctx.getFacesContext().getELContext();
                ValueExpression expr = ctx.getExpressionFactory().createValueExpression(
                        elCtx, expStr.toString(), Object.class);
                expr.setValue(elCtx, veObj.getValue(elCtx));
            }
            else
            {
                ctx.getVariableMapper().setVariable(varStr, veObj);
                AbstractFaceletContext actx = ((AbstractFaceletContext) ctx);
                actx.getPageContext().setAllowCacheELExpressions(false);
            }
        }
        else
        {
            // Check attributes
            if (this.target == null || this.property == null || this.value == null)
            {
                throw new TagException(
                        tag, "either attributes var and value or target, property and value must be set");
            }
            if (this.target.isLiteral())
            {
                throw new TagException(tag, "attribute target must contain a value expression");
            }

            // Get target object and name of property to set
            ELContext elCtx = ctx.getFacesContext().getELContext();
            ValueExpression targetExpr = this.target.getValueExpression(ctx, Object.class);
            Object targetObj = targetExpr.getValue(elCtx);
            String propertyName = this.property.getValue(ctx);
            // Set property on target object
            ctx.getELResolver().setValue(elCtx, targetObj, propertyName, veObj.getValue(elCtx));
        }
    }
}
