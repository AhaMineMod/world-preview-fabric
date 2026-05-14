package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.storage.PreviewSection;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Arrays;
import java.util.List;

public record WorkResult(
        WorkUnit workUnit,
        int quartY,
        PreviewSection section,
        BlockResults results,
        List<com.mojang.datafixers.util.Pair<net.minecraft.resources.Identifier, StructureStart>> structures
) {

    public static final class BlockResults {
        private int[] quartXs;
        private int[] quartZs;
        private short[] values;
        private int size;

        public BlockResults(int expectedSize) {
            int capacity = Math.max(1, expectedSize);
            quartXs = new int[capacity];
            quartZs = new int[capacity];
            values = new short[capacity];
        }

        public void add(int quartX, int quartZ, short value) {
            ensureCapacity(size + 1);
            quartXs[size] = quartX;
            quartZs[size] = quartZ;
            values[size] = value;
            size++;
        }

        public int size() {
            return size;
        }

        public int quartX(int index) {
            return quartXs[index];
        }

        public int quartZ(int index) {
            return quartZs[index];
        }

        public short value(int index) {
            return values[index];
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= values.length) {
                return;
            }
            int newCapacity = Math.max(minCapacity, values.length * 2);
            quartXs = Arrays.copyOf(quartXs, newCapacity);
            quartZs = Arrays.copyOf(quartZs, newCapacity);
            values = Arrays.copyOf(values, newCapacity);
        }
    }
}
