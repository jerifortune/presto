/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.presto.operator.aggregation.TypedSet;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.Description;
import com.facebook.presto.spi.function.ScalarFunction;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.function.TypeParameter;
import com.facebook.presto.spi.type.MapType;
import com.facebook.presto.spi.type.RowType;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static java.lang.String.format;

@ScalarFunction("map_from_entries")
@Description("construct a map from an array of entries")
public final class MapFromEntriesFunction
{
    private final PageBuilder pageBuilder;

    @TypeParameter("K")
    @TypeParameter("V")
    public MapFromEntriesFunction(@TypeParameter("map(K,V)") Type mapType)
    {
        pageBuilder = new PageBuilder(ImmutableList.of(mapType));
    }

    @TypeParameter("K")
    @TypeParameter("V")
    @SqlType("map(K,V)")
    public Block mapFromEntries(
            @TypeParameter("map(K,V)") MapType mapType,
            ConnectorSession session,
            @SqlType("array(row(K,V))") Block block)
    {
        Type keyType = mapType.getKeyType();
        Type valueType = mapType.getValueType();
        RowType rowType = new RowType(ImmutableList.of(keyType, valueType), Optional.empty());

        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        int entryCount = block.getPositionCount();

        BlockBuilder mapBlockBuilder = pageBuilder.getBlockBuilder(0);
        BlockBuilder resultBuilder = mapBlockBuilder.beginBlockEntry();
        TypedSet uniqueKeys = new TypedSet(keyType, entryCount);

        for (int i = 0; i < entryCount; i++) {
            Block rowBlock = rowType.getObject(block, i);

            if (rowBlock.isNull(0)) {
                mapBlockBuilder.closeEntry();
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "map key cannot be null");
            }

            if (uniqueKeys.contains(rowBlock, 0)) {
                mapBlockBuilder.closeEntry();
                throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Duplicate keys (%s) are not allowed", keyType.getObjectValue(session, rowBlock, 0)));
            }
            uniqueKeys.add(rowBlock, 0);

            keyType.appendTo(rowBlock, 0, resultBuilder);
            valueType.appendTo(rowBlock, 1, resultBuilder);
        }

        mapBlockBuilder.closeEntry();
        pageBuilder.declarePosition();
        return mapType.getObject(mapBlockBuilder, mapBlockBuilder.getPositionCount() - 1);
    }
}
