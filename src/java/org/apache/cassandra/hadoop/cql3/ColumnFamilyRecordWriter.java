/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.hadoop.cql3;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cassandra.thrift.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.hadoop.AbstractColumnFamilyRecordWriter;
import org.apache.cassandra.hadoop.ConfigHelper;
import org.apache.cassandra.hadoop.Progressable;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ColumnFamilyRecordWriter</code> maps the output &lt;key, value&gt;
 * pairs to a Cassandra column family. In particular, it applies the binded variables
 * in the value to the prepared statement, which it associates with the key, and in 
 * turn the responsible endpoint.
 *
 * <p>
 * Furthermore, this writer groups the cql queries by the endpoint responsible for
 * the rows being affected. This allows the cql queries to be executed in parallel,
 * directly to a responsible endpoint.
 * </p>
 *
 * @see ColumnFamilyOutputFormat
 */
final class ColumnFamilyRecordWriter extends AbstractColumnFamilyRecordWriter<Map<String, ByteBuffer>, List<ByteBuffer>>
{
    private static final Logger logger = LoggerFactory.getLogger(ColumnFamilyRecordWriter.class);

    // handles for clients for each range running in the threadpool
    private final Map<Range, RangeClient> clients;

    // host to prepared statement id mappings
    private ConcurrentHashMap<Cassandra.Client, Integer> preparedStatements = new ConcurrentHashMap<Cassandra.Client, Integer>();

    private final String cql;

    private AbstractType<?> keyValidator;
    private String [] partitionkeys;

    /**
     * Upon construction, obtain the map that this writer will use to collect
     * mutations, and the ring cache for the given keyspace.
     *
     * @param context the task attempt context
     * @throws IOException
     */
    ColumnFamilyRecordWriter(TaskAttemptContext context) throws IOException
    {
        this(context.getConfiguration());
        this.progressable = new Progressable(context);
    }

    ColumnFamilyRecordWriter(Configuration conf, Progressable progressable) throws IOException
    {
        this(conf);
        this.progressable = progressable;
    }

    ColumnFamilyRecordWriter(Configuration conf) throws IOException
    {
        super(conf);
        this.clients = new HashMap<Range, RangeClient>();
        cql = CQLConfigHelper.getOutputCql(conf);

        try
        {
            String host = getAnyHost();
            int port = ConfigHelper.getOutputRpcPort(conf);
            Cassandra.Client client = ColumnFamilyOutputFormat.createAuthenticatedClient(host, port, conf);
            retrievePartitionKeyValidator(client);
            
            if (client != null)
            {
                TTransport transport = client.getOutputProtocol().getTransport();
                if (transport.isOpen())
                    transport.close();
                client = null;
            }
        }
        catch (Exception e)
        {
            throw new IOException(e);
        }
    }
    
    @Override
    public void close() throws IOException
    {
        // close all the clients before throwing anything
        IOException clientException = null;
        for (RangeClient client : clients.values())
        {
            try
            {
                client.close();
            }
            catch (IOException e)
            {
                clientException = e;
            }
        }

        if (clientException != null)
            throw clientException;
    }
    
    /**
     * If the key is to be associated with a valid value, a mutation is created
     * for it with the given column family and columns. In the event the value
     * in the column is missing (i.e., null), then it is marked for
     * {@link Deletion}. Similarly, if the entire value for a key is missing
     * (i.e., null), then the entire key is marked for {@link Deletion}.
     * </p>
     *
     * @param keybuff
     *            the key to write.
     * @param values
     *            the values to write.
     * @throws IOException
     */
    @Override
    public void write(Map<String, ByteBuffer> keys, List<ByteBuffer> values) throws IOException
    {
        ByteBuffer rowKey = getRowKey(keys);
        Range<Token> range = ringCache.getRange(rowKey);

        // get the client for the given range, or create a new one
        RangeClient client = clients.get(range);
        if (client == null)
        {
            // haven't seen keys for this range: create new client
            client = new RangeClient(ringCache.getEndpoint(range));
            client.start();
            clients.put(range, client);
        }

        client.put(Pair.create(rowKey, values));
        progressable.progress();
    }

    /**
     * A client that runs in a threadpool and connects to the list of endpoints for a particular
     * range. Binded variable values for keys in that range are sent to this client via a queue.
     */
    public class RangeClient extends AbstractRangeClient<List<ByteBuffer>>
    {
        /**
         * Constructs an {@link RangeClient} for the given endpoints.
         * @param endpoints the possible endpoints to execute the mutations on
         */
        public RangeClient(List<InetAddress> endpoints)
        {
            super(endpoints);
         }
        
        /**
         * Loops collecting cql binded variable values from the queue and sending to Cassandra
         */
        public void run()
        {
            outer:
            while (run || !queue.isEmpty())
            {
                Pair<ByteBuffer, List<ByteBuffer>> bindVariables;
                try
                {
                    bindVariables = queue.take();
                }
                catch (InterruptedException e)
                {
                    // re-check loop condition after interrupt
                    continue;
                }

                Iterator<InetAddress> iter = endpoints.iterator();
                while (true)
                {
                    // send the mutation to the last-used endpoint.  first time through, this will NPE harmlessly.
                    try
                    {
                        int i = 0;
                        int itemId = preparedStatement(client);
                        while (bindVariables != null)
                        {
                            client.execute_prepared_cql3_query(itemId, bindVariables.right, ConsistencyLevel.ONE);
                            i++;
                            
                            if (i >= batchThreshold)
                                break;
                            
                            bindVariables = queue.poll();
                        }
                        
                        break;
                    }
                    catch (Exception e)
                    {
                        closeInternal();
                        if (!iter.hasNext())
                        {
                            lastException = new IOException(e);
                            break outer;
                        }
                    }

                    // attempt to connect to a different endpoint
                    try
                    {
                        InetAddress address = iter.next();
                        String host = address.getHostName();
                        int port = ConfigHelper.getOutputRpcPort(conf);
                        client = ColumnFamilyOutputFormat.createAuthenticatedClient(host, port, conf);
                    }
                    catch (Exception e)
                    {
                        closeInternal();
                        // TException means something unexpected went wrong to that endpoint, so
                        // we should try again to another.  Other exceptions (auth or invalid request) are fatal.
                        if ((!(e instanceof TException)) || !iter.hasNext())
                        {
                            lastException = new IOException(e);
                            break outer;
                        }
                    }
                }
            }
        }

        /** get prepared statement id from cache, otherwise prepare it from Cassandra server*/
        private int preparedStatement(Cassandra.Client client)
        {
            Integer itemId = preparedStatements.get(client);
            if (itemId == null)
            {
                CqlPreparedResult result;
                try
                {
                    result = client.prepare_cql3_query(ByteBufferUtil.bytes(cql), Compression.NONE);
                }
                catch (InvalidRequestException e)
                {
                    throw new RuntimeException("failed to prepare cql query " + cql, e);
                }
                catch (TException e)
                {
                    throw new RuntimeException("failed to prepare cql query " + cql, e);
                }

                Integer previousId = preparedStatements.putIfAbsent(client, Integer.valueOf(result.itemId));
                itemId = previousId == null ? result.itemId : previousId;
            }
            return itemId;
        }
    }

    private ByteBuffer getRowKey(Map<String, ByteBuffer> keysMap)
    {
        //current row key
        ByteBuffer rowKey;
        if (keyValidator instanceof CompositeType)
        {
            ByteBuffer[] keys = new ByteBuffer[partitionkeys.length];
            for (int i = 0; i< keys.length; i++)
                keys[i] = keysMap.get(partitionkeys[i]);

            rowKey = ((CompositeType) keyValidator).build(keys);
        }
        else
        {
            rowKey = keysMap.get(partitionkeys[0]);
        }
        return rowKey;
    }

    /** retrieve the key validator from system.schema_columnfamilies table */
    private void retrievePartitionKeyValidator(Cassandra.Client client) throws Exception
    {
        String keyspace = ConfigHelper.getOutputKeyspace(conf);
        String cfName = ConfigHelper.getOutputColumnFamily(conf);
        String query = "SELECT key_validator," +
        		       "       key_aliases " +
                       "FROM system.schema_columnfamilies " +
                       "WHERE keyspace_name='%s' and columnfamily_name='%s'";
        String formatted = String.format(query, keyspace, cfName);
        CqlResult result = client.execute_cql3_query(ByteBufferUtil.bytes(formatted), Compression.NONE, ConsistencyLevel.ONE);

        Column rawKeyValidator = result.rows.get(0).columns.get(0);
        String validator = ByteBufferUtil.string(ByteBuffer.wrap(rawKeyValidator.getValue()));
        keyValidator = parseType(validator);
        
        Column rawPartitionKeys = result.rows.get(0).columns.get(1);
        String keyString = ByteBufferUtil.string(ByteBuffer.wrap(rawPartitionKeys.getValue()));
        logger.debug("partition keys: " + keyString);
        
        List<String> keys = FBUtilities.fromJsonList(keyString);
        partitionkeys = new String [keys.size()];
        int i=0;
        for (String key: keys)
        {
            partitionkeys[i] = key;
            i++;
        }
    }

    private AbstractType<?> parseType(String type) throws IOException
    {
        try
        {
            // always treat counters like longs, specifically CCT.compose is not what we need
            if (type != null && type.equals("org.apache.cassandra.db.marshal.CounterColumnType"))
                return LongType.instance;
            return TypeParser.parse(type);
        }
        catch (ConfigurationException e)
        {
            throw new IOException(e);
        }
        catch (SyntaxException e)
        {
            throw new IOException(e);
        }
    }
    
    private String getAnyHost() throws IOException, InvalidRequestException, TException
    {
        Cassandra.Client client = ConfigHelper.getClientFromOutputAddressList(conf);
        List<TokenRange> ring = client.describe_ring(ConfigHelper.getOutputKeyspace(conf));
        try
        {
            for (TokenRange range : ring)
                return range.endpoints.get(0);
        }
        finally
        {
            if (client != null)
            {
                TTransport transport = client.getOutputProtocol().getTransport();
                if (transport.isOpen())
                    transport.close();
                client = null;
            }
        }
        throw new IOException("There are no endpoints");
    }

}