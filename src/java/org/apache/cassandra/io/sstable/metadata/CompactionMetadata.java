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
package org.apache.cassandra.io.sstable.metadata;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.sstable.Descriptor;

/**
 * Compaction related SSTable metadata.
 *
 * Only loaded for <b>compacting</b> SSTables at the time of compaction.
 */
public class CompactionMetadata extends MetadataComponent
{
    public static final IMetadataComponentSerializer serializer = new CompactionMetadataSerializer();

    public final Set<Integer> ancestors;

    public CompactionMetadata(Set<Integer> ancestors)
    {
        this.ancestors = ancestors;
    }

    public MetadataType getType()
    {
        return MetadataType.COMPACTION;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CompactionMetadata that = (CompactionMetadata) o;
        return ancestors == null ? that.ancestors == null : ancestors.equals(that.ancestors);
    }

    @Override
    public int hashCode()
    {
        return ancestors != null ? ancestors.hashCode() : 0;
    }

    public static class CompactionMetadataSerializer implements IMetadataComponentSerializer<CompactionMetadata>
    {
        public int serializedSize(CompactionMetadata component) throws IOException
        {
            int size = 0;
            size += TypeSizes.NATIVE.sizeof(component.ancestors.size());
            for (int g : component.ancestors)
                size += TypeSizes.NATIVE.sizeof(g);
            return size;
        }

        public void serialize(CompactionMetadata component, DataOutput out) throws IOException
        {
            out.writeInt(component.ancestors.size());
            for (int g : component.ancestors)
                out.writeInt(g);
        }

        public CompactionMetadata deserialize(Descriptor.Version version, DataInput in) throws IOException
        {
            int nbAncestors = in.readInt();
            Set<Integer> ancestors = new HashSet<>(nbAncestors);
            for (int i = 0; i < nbAncestors; i++)
                ancestors.add(in.readInt());
            return new CompactionMetadata(ancestors);
        }
    }
}
