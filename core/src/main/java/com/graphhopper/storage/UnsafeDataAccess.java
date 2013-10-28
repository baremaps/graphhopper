/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import static com.graphhopper.storage.AbstractDataAccess.HEADER_OFFSET;
import com.graphhopper.util.NotThreadSafe;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteOrder;

/**
 * This is a data structure which uses an unsafe access to native memory. Notes:
 * <p>
 * 1. Highly experimental. Several TODOs like bug fixing and access through file/MMAP working
 * <p>
 * 2. Compared to MMAP no syncDAWrapper is need to make it safe from multiple threads
 * <p>
 * 3. Cannot be used on Android I think
 * <p/>
 * @author Peter Karich
 */
@NotThreadSafe
public class UnsafeDataAccess extends AbstractDataAccess
{
    @SuppressWarnings("ALL")
    static final sun.misc.Unsafe UNSAFE;

    static
    {
        try
        {
            @SuppressWarnings("ALL")
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) field.get(null);
        } catch (Exception e)
        {
            throw new AssertionError(e);
        }
    }

    private long address;
    private long capacity;
    private transient boolean closed = false;

    UnsafeDataAccess( String name, String location, ByteOrder order )
    {
        super(name, location, order);
    }

    @Override
    public UnsafeDataAccess create( long bytes )
    {
        // TODO use unsafe.pageSize() instead segmentSizeInBytes?
        // e.g. on my system pageSize is only 4096
        setSegmentSize(segmentSizeInBytes);
        ensureCapacity(bytes);
        return this;
    }

    @Override
    public void ensureCapacity( long bytes )
    {
        ensureCapacity(bytes, true);
    }

    void ensureCapacity( long bytes, boolean clearNewMem )
    {
        long oldCap = getCapacity();
        long todoBytes = bytes - oldCap;
        if (todoBytes <= 0)
            return;

        // avoid frequent increase of allocation area, instead increase by segment size
        int allSegments = (int) (bytes / segmentSizeInBytes);
        if (bytes % segmentSizeInBytes != 0)
            allSegments++;
        capacity = allSegments * segmentSizeInBytes;

        try
        {
            address = UNSAFE.reallocateMemory(address, capacity);
        } catch (OutOfMemoryError err)
        {
            throw new OutOfMemoryError(err.getMessage() + " - problem when allocating new memory. Old capacity: "
                    + oldCap + ", new bytes:" + todoBytes + ", segmentSizeIntsPower:" + segmentSizePower);
        }

        if (clearNewMem)
            UNSAFE.setMemory(address + oldCap, todoBytes, (byte) 0);
    }

    @Override
    public DataAccess copyTo( DataAccess da )
    {
        if (da instanceof UnsafeDataAccess)
        {
            // TODO
            // unsafe.copyMemory(address, da.address, capacity);
            // return this;
        }
        return super.copyTo(da);
    }

    @Override
    public boolean loadExisting()
    {
        if (closed)
            return false;

        File file = new File(getFullName());
        if (!file.exists() || file.length() == 0)
            return false;

        try
        {
            RandomAccessFile raFile = new RandomAccessFile(getFullName(), "r");
            try
            {
                long byteCount = readHeader(raFile) - HEADER_OFFSET;
                if (byteCount < 0)
                    return false;

                raFile.seek(HEADER_OFFSET);
                int segmentCount = (int) (byteCount / segmentSizeInBytes);
                if (byteCount % segmentSizeInBytes != 0)
                    segmentCount++;

                ensureCapacity(byteCount, false);
                byte[] bytes = new byte[segmentSizeInBytes];
                for (int s = 0; s < segmentCount; s++)
                {
                    int read = raFile.read(bytes);
                    if (read <= 0)
                        throw new IllegalStateException("segment " + s + " is empty? " + toString());

                    // is there a faster method?
                    setBytes(s * segmentSizeInBytes, bytes, segmentSizeInBytes);
                }
                return true;
            } finally
            {
                raFile.close();
            }
        } catch (IOException ex)
        {
            throw new RuntimeException("Problem while loading " + getFullName(), ex);
        }
    }

    @Override
    public void flush()
    {
        if (closed)
            throw new IllegalStateException("already closed");

        try
        {
            RandomAccessFile raFile = new RandomAccessFile(getFullName(), "rw");
            try
            {
                long len = getCapacity();
                writeHeader(raFile, len, segmentSizeInBytes);
                raFile.seek(HEADER_OFFSET);
                byte bytes[] = new byte[segmentSizeInBytes];
                int segs = getSegments();
                for (int s = 0; s < segs; s++)
                {
                    getBytes(s * segmentSizeInBytes, bytes, segmentSizeInBytes);
                    raFile.write(bytes);
                }
            } finally
            {
                raFile.close();
            }
        } catch (Exception ex)
        {
            throw new RuntimeException("Couldn't store bytes to " + toString(), ex);
        }
    }

    @Override
    public void close()
    {
        closed = true;
        UNSAFE.freeMemory(address);
    }

    @Override
    public final void setInt( long bytePos, int value )
    {
        UNSAFE.putInt(address + bytePos, value);
    }

    @Override
    public final int getInt( long bytePos )
    {
        return UNSAFE.getInt(address + bytePos);
    }

    @Override
    public void setBytes( long bytePos, byte[] values, int length )
    {
        for (int offset = 0; offset < length; offset++)
        {
            UNSAFE.putByte(address + bytePos + offset, values[offset]);
        }
    }

    @Override
    public void getBytes( long bytePos, byte[] values, int length )
    {
        assert length <= segmentSizeInBytes : "the length has to be smaller or equal to the segment size: " + length + " vs. " + segmentSizeInBytes;
        for (int offset = 0; offset < length; offset++)
        {
            values[offset] = UNSAFE.getByte(address + bytePos + offset);
        }
    }

    @Override
    public long getCapacity()
    {
        return capacity;
    }

    @Override
    public int getSegments()
    {
        return (int) (capacity / segmentSizeInBytes);
    }

    @Override
    public void trimTo( long bytes )
    {
        if (bytes > this.capacity)
            throw new IllegalStateException("Use ensureCapacity to increase capacity!");

        int allSegments = (int) (bytes / segmentSizeInBytes);
        if (bytes % segmentSizeInBytes != 0)
            allSegments++;
        if (allSegments <= 0)
            allSegments = 1;
        capacity = allSegments * segmentSizeInBytes;
    }

    @Override
    public DAType getType()
    {
        return DAType.UNSAFE_STORE;
    }
}
