//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import org.junit.After;
import org.junit.Before;



/**
 * ImmediateSaveTest
 *
 *
 */
public class ImmediateSaveTest extends AbstractImmediateSaveTest
{
    @Before
    public void before() throws Exception
    {
        FileTestServer.setup();
    }

    @After 
    public void after()
    {
        FileTestServer.teardown();
    }


    public  AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy)
    {        return new FileTestServer(port, max, scavenge, evictionPolicy) 
    {
        public SessionHandler newSessionHandler()
        {
            SessionHandler h = super.newSessionHandler();
            h.getSessionCache().setSaveOnCreate(true);
            return h;
        }
    };
    }

}