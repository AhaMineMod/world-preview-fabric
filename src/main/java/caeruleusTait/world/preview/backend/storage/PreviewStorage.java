package caeruleusTait.world.preview.backend.storage;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;

import static caeruleusTait.world.preview.backend.WorkManager.Y_BLOCK_SHIFT;

public class PreviewStorage implements Serializable {

    @Serial
    private static final long serialVersionUID = -275836689822028264L;

    public static final long FLAG_BITS = 4;
    public static final long FLAG_MASK = (1L << FLAG_BITS) - 1L;

    public static final long XZ_BITS = 30;
    public static final long XZ_MASK = (1L << XZ_BITS) - 1L;

    public static final long FLAG_SHIFT = 0L;
    public static final long Z_SHIFT = FLAG_SHIFT + FLAG_BITS;
    public static final long X_SHIFT = Z_SHIFT + XZ_BITS;

    public static final long FLAG_BIOME = 0b0000;
    public static final long FLAG_STRUCT_START = 0b0001;
    public static final long FLAG_HEIGHT = 0b0010;
    public static final long FLAG_INTERSECT = 0b0011;
    public static final long FLAG_NOISE_TEMPERATURE = 0b1001;
    public static final long FLAG_NOISE_HUMIDITY = 0b1010;
    public static final long FLAG_NOISE_CONTINENTALNESS = 0b1011;
    public static final long FLAG_NOISE_EROSION = 0b1100;
    public static final long FLAG_NOISE_DEPTH = 0b1101;
    public static final long FLAG_NOISE_WEIRDNESS = 0b1110;

    private transient Long2ObjectMap<PreviewBlock>[] blocks;

    private final int yMin;
    private final int yMax;

    @SuppressWarnings("unchecked")
    public PreviewStorage(int yMin, int yMax) {
        blocks = new Long2ObjectMap[((yMax - yMin) >> Y_BLOCK_SHIFT) + 1];
        for (int i = 0; i < blocks.length; ++i) {
            blocks[i] = new Long2ObjectOpenHashMap<>(1024, Hash.FAST_LOAD_FACTOR);
        }
        this.yMin = yMin;
        this.yMax = yMax;
    }

    public PreviewSection section4(ChunkPos chunkPos, int y, long flags) {
        final int quartX = QuartPos.fromSection(chunkPos.x);
        final int quartZ = QuartPos.fromSection(chunkPos.z);
        return getOrCreateSection(quartX, yIndexForBlockY(y), quartZ, flags);
    }

    public @Nullable PreviewSection getExistingSection4(int quartX, int quartY, int quartZ, long flags) {
        return getExistingSection(quartX, yIndexForQuartY(quartY), quartZ, flags);
    }

    /**
     * Returns {@link Short#MIN_VALUE} when not found. Only use this when querying a single position!
     */
    public short getRawData4(int quartX, int quartY, int quartZ, long flags) {
        final PreviewSection section = getExistingSection(quartX, yIndexForQuartY(quartY), quartZ, flags);
        if (section == null) {
            return Short.MIN_VALUE;
        }
        return section.get(quartX - section.quartX(), quartZ - section.quartZ());
    }

    private int yIndexForQuartY(int quartY) {
        return yIndexForBlockY(QuartPos.toBlock(quartY));
    }

    private int yIndexForBlockY(int blockY) {
        final int indexY = (blockY - yMin) >> Y_BLOCK_SHIFT;
        if (indexY < 0 || indexY >= blocks.length) {
            throw new IndexOutOfBoundsException("Y outside preview storage bounds: " + blockY + " not in [" + yMin + ", " + yMax + "]");
        }
        return indexY;
    }

    private PreviewSection getOrCreateSection(int quartX, int indexY, int quartZ, long flags) {
        final PreviewBlock block;
        synchronized (blocks[indexY]) {
            block = blocks[indexY].computeIfAbsent(quartPosToSectionLong(quartX, quartZ, flags), x -> new PreviewBlock(flags));
        }
        return block.get(quartX, quartZ);
    }

    private @Nullable PreviewSection getExistingSection(int quartX, int indexY, int quartZ, long flags) {
        final PreviewBlock block = getExistingBlock(indexY, quartX, quartZ, flags);
        return block == null ? null : block.getExisting(quartX, quartZ);
    }

    private @Nullable PreviewBlock getExistingBlock(int indexY, int quartX, int quartZ, long flags) {
        synchronized (blocks[indexY]) {
            return blocks[indexY].get(quartPosToSectionLong(quartX, quartZ, flags));
        }
    }

    public static long quartPosToSectionLong(long quartX, long quartZ, long flags) {
        final long sX = quartX >> (PreviewSection.SHIFT + PreviewBlock.PREVIEW_BLOCK_SHIFT);
        final long sZ = quartZ >> (PreviewSection.SHIFT + PreviewBlock.PREVIEW_BLOCK_SHIFT);
        return (sX & XZ_MASK) << X_SHIFT | (sZ & XZ_MASK) << Z_SHIFT | (flags & FLAG_MASK) << FLAG_SHIFT;
    }

    @Serial
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();

        // Write the sections
        oos.writeInt(blocks.length);
        for (Long2ObjectMap<PreviewBlock> ySec : blocks) {
            final var entrySet = ySec.long2ObjectEntrySet();
            oos.writeInt(entrySet.size());
            for (var x : entrySet) {
                oos.writeLong(x.getLongKey());
                oos.writeObject(x.getValue());
            }
        }
    }

    @Serial
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();

        // Read the sections
        blocks = new Long2ObjectMap[((yMax - yMin) >> Y_BLOCK_SHIFT) + 1];

        final int serializedSize = ois.readInt();
        if (serializedSize != blocks.length) {
            throw new IOException("serializedSize != sections.length: " + serializedSize + " != " + blocks.length);
        }

        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new Long2ObjectOpenHashMap<>(1024, Hash.FAST_LOAD_FACTOR);
            final int size = ois.readInt();
            for (int j = 0; j < size; ++j) {
                final long key = ois.readLong();
                final PreviewBlock section = (PreviewBlock) ois.readObject();
                blocks[i].put(key, section);
            }
        }
    }

}
