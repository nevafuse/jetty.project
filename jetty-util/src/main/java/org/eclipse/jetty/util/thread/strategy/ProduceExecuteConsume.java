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

package org.eclipse.jetty.util.thread.strategy;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;

/**
 * <p>A strategy where the caller thread iterates over task production, submitting each
 * task to an {@link Executor} for execution.</p>
 */
public class ProduceExecuteConsume implements ExecutionStrategy
{
    private static final Logger LOG = Log.getLogger(ExecutionStrategy.class);
    private final Producer _producer;
    private final Executor _executor;

    public ProduceExecuteConsume(Producer producer, Executor executor)
    {
        this._producer = producer;
        this._executor = executor;
    }

    @Override
    public void execute()
    {
        // Iterate until we are complete.
        while (true)
        {
            // Produce a task.
            Runnable task = _producer.produce();
            if (LOG.isDebugEnabled())
                LOG.debug("{} PER produced {}",_producer,task);

            if (task == null)
                break;

            // Execute the task.
            try
            {
                _executor.execute(task);
            }
            catch(RejectedExecutionException e)
            {
                // Close or discard tasks that cannot be executed
                if (task instanceof Rejectable)
                {
                    try
                    {
                        ((Rejectable)task).reject();
                    }
                    catch (Throwable x)
                    {
                        e.addSuppressed(x);
                        LOG.warn(e);
                    }
                }
            }
        }
    }

    @Override
    public void dispatch()
    {
        execute();
    }
}