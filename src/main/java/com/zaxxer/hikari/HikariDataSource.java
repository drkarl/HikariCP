/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.hikari;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.zaxxer.hikari.util.DriverDataSource;

/**
 * The HikariCP pooled DataSource.
 *
 * @author Brett Wooldridge
 */
public class HikariDataSource extends HikariConfig implements DataSource
{
    private volatile boolean isShutdown;
    private int loginTimeout;

    /* Package scopped for testing */
    ConcurrentHashMap<String, HikariPool> multiPool;
    final HikariPool fastPathPool;
    volatile HikariPool pool;

    /**
     * Default constructor.  Setters be used to configure the pool.  Using
     * this constructor vs. {@link #HikariDataSource(HikariConfig)} will
     * result in {@link #getConnection()} performance that is slightly lower
     * due to lazy initialization checks.
     */
    public HikariDataSource()
    {
    	super();
    	fastPathPool = null;
    	multiPool = new ConcurrentHashMap<String, HikariPool>();
    }

    /**
     * Construct a HikariDataSource with the specified configuration.
     *
     * @param configuration a HikariConfig instance
     */
    public HikariDataSource(HikariConfig configuration)
    {
        configuration.validate();
    	configuration.copyState(this);
        multiPool = new ConcurrentHashMap<String, HikariPool>();
    	pool = fastPathPool = new HikariPool(this);
    }

    /** {@inheritDoc} */
    @Override
    public Connection getConnection() throws SQLException
    {
        if (isShutdown)
        {
            throw new SQLException("Pool has been shutdown");
        }
        
        if (fastPathPool != null)
        {
        	return fastPathPool.getConnection();
        }

        HikariPool result = pool;
    	if (result == null)
    	{
    		synchronized (this)
    		{
    			result = pool;
    			if (result == null)
    			{
    			    validate();
    				pool = result = new HikariPool(this);
    			}
    		}
    	}

        return result.getConnection();
    }

    /** {@inheritDoc} */
    @Override
    public Connection getConnection(String username, String password) throws SQLException
    {
        if (isShutdown)
        {
            throw new SQLException("Pool has been shutdown");
        }

        HikariPool hikariPool;
        synchronized (multiPool)
        {
            hikariPool = multiPool.get(username + password);
            if (hikariPool == null)
            {
                hikariPool = new HikariPool(this, username, password);
                multiPool.put(username + password, hikariPool);
            }
        }

        return hikariPool.getConnection();
    }

    /** {@inheritDoc} */
    @Override
    public PrintWriter getLogWriter() throws SQLException
    {
        return (pool.dataSource != null ? pool.dataSource.getLogWriter() : null);
    }

    /** {@inheritDoc} */
    @Override
    public void setLogWriter(PrintWriter out) throws SQLException
    {
        if (pool.dataSource != null)
        {
            pool.dataSource.setLogWriter(out);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setLoginTimeout(int seconds) throws SQLException
    {
        this.loginTimeout = seconds;
    }

    /** {@inheritDoc} */
    @Override
    public int getLoginTimeout() throws SQLException
    {
        return loginTimeout;
    }

    /** {@inheritDoc} */
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw new SQLFeatureNotSupportedException();
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        if (pool != null && iface.isInstance(pool.dataSource))
        {
            return (T) pool.dataSource;
        }

        throw new SQLException("Wrapped connection is not an instance of " + iface);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        return (this.getClass().isAssignableFrom(iface));
    }

    /**
     * <code>close()</code> and <code>shutdown()</code> are synonymous.
     */
    public void close()
    {
        shutdown();
    }

    /**
     * Shutdown the DataSource and its associated pool.
     */
    public void shutdown()
    {
        if (isShutdown)
        {
            return;
        }

        isShutdown = true;
        
        if (pool != null)
        {
            pool.shutdown();
            if (pool.dataSource instanceof DriverDataSource)
            {
                ((DriverDataSource) pool.dataSource).shutdown();
            }
        }

        if (!multiPool.isEmpty())
        {
            for (HikariPool hikariPool : multiPool.values())
            {
                hikariPool.shutdown();
                if (hikariPool.dataSource instanceof DriverDataSource)
                {
                    ((DriverDataSource) hikariPool.dataSource).shutdown();
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public String toString()
    {
        return String.format("HikariDataSource (%s)", pool);
    }
}
