package org.apache.ace.deployment.processor;
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


import java.io.IOException;
import java.io.InputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import aQute.bnd.annotation.ProviderType;

/**
 * Deployment processors can post process the deployment package before it is sent to the original requester. This allows you to
 * transform how the data is actually sent, allowing you to basically "repackage" the content on the fly. Deployment processors
 * should be registered as services with a service property called "processor" which should match a request property of the same
 * name and value.
 */
@ProviderType
public interface DeploymentProcessor {
    /**
     * Post-process the stream and send it to the response. The input stream is the deployment package as it was generated. You
     * should set the correct mime type and start writing to the response.
     * 
     * @param inputStream the input stream for the deployment package
     * @param request the original request, in case you want to access certain parameters
     * @param response the response to write to
     */
    public void process(InputStream inputStream, HttpServletRequest request, HttpServletResponse response) throws IOException;
}
