package com.aravpanwar.mcdiff;

import java.util.Set;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DryVegetationBlock;
import net.minecraft.world.level.block.FireflyBushBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides which blocks the diff looks at and which ones count as the same.
 * Every rule works on block types, so it applies equally to blocks read from
 * the live world and to blocks read back from an older saved baseline.
 */
final class Purifier {
	/** Bumped whenever these rules change, so totals worked out under older rules are thrown away. */
	static final int RULES_VERSION = 2;

	/**
	 * Marks a baseline position whose original block isn't known for sure.
	 * Structure voids never generate naturally, so the marker can't be
	 * confused with real terrain.
	 */
	static final Block UNKNOWN = Blocks.STRUCTURE_VOID;

	private static final Set<Block> UNDERWATER_PLANTS = Set.of(
			Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.BUBBLE_COLUMN);

	private static final Set<Block> COMES_AND_GOES = Set.of(
			Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.POINTED_DRIPSTONE, Blocks.SULFUR_SPIKE,
			Blocks.ICE, Blocks.FROSTED_ICE, Blocks.MOVING_PISTON);

	private static final Set<Block> NATURAL_TERRAIN = Set.of(
			Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE,
			Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.DRIPSTONE_BLOCK, Blocks.MAGMA_BLOCK,
			Blocks.SCULK, Blocks.SULFUR, Blocks.CINNABAR,
			Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE,
			Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
			Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
			Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
			Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.GRASS_BLOCK, Blocks.MUD,
			Blocks.SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.SNOW_BLOCK,
			Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE);

	private static final Set<Block> HARDENED_LIQUID = Set.of(Blocks.OBSIDIAN, Blocks.COBBLESTONE, Blocks.STONE, Blocks.BASALT);

	private Purifier() {
	}

	static Block purify(BlockState state) {
		return state.isAir() ? Blocks.AIR : purify(state.getBlock());
	}

	/*
	 * The diff compares block types, never block states, so doors swinging and
	 * crops growing don't show up. Water and lava are kept as themselves rather
	 * than collapsed to air, so stone or obsidian found where a liquid used to
	 * be can be recognised as the liquid hardening on its own. Everything else
	 * that comes and goes by itself (leaves, ice, fire, snow layers, creeping
	 * and spreading plants, grass tufts and flowers) collapses to air.
	 */
	static Block purify(Block block) {
		if (block.defaultBlockState().isAir()) {
			return Blocks.AIR;
		}
		if (block == Blocks.WATER || block == Blocks.LAVA) {
			return block;
		}
		if (block instanceof BubbleColumnBlock || UNDERWATER_PLANTS.contains(block)) {
			return Blocks.WATER;
		}
		if (block instanceof LeavesBlock
				|| block instanceof BaseFireBlock
				|| block instanceof SnowLayerBlock
				|| COMES_AND_GOES.contains(block)
				|| isGroundCover(block)) {
			return Blocks.AIR;
		}
		return block;
	}

	static boolean isEmpty(Block block) {
		return block == Blocks.AIR || block == Blocks.WATER || block == Blocks.LAVA;
	}

	/*
	 * Where two chunks' ore blobs, dirt and gravel patches or sculk overlap,
	 * the block that wins depends on which chunk generated first, so terrain
	 * rebuilt from the seed disagrees with the real world about which one sits
	 * where. Treating all natural terrain as one block hides that, while
	 * digging any of it out still counts.
	 */
	static boolean alike(Block before, Block now) {
		return before == now
				|| before == UNKNOWN
				|| now == UNKNOWN
				|| (isEmpty(before) && isEmpty(now))
				|| (NATURAL_TERRAIN.contains(before) && NATURAL_TERRAIN.contains(now))
				|| ((before == Blocks.WATER || before == Blocks.LAVA) && HARDENED_LIQUID.contains(now));
	}

	/*
	 * Grass tufts, flowers and mushrooms are scattered by neighbouring chunks
	 * too, so they land in different spots when terrain is rebuilt, and they
	 * come and go with bone meal and trampling anyway. Crops and saplings are
	 * left alone because they are usually planted on purpose.
	 */
	private static boolean isGroundCover(Block block) {
		return block instanceof TallGrassBlock
				|| block instanceof FlowerBlock
				|| block instanceof MushroomBlock
				|| block instanceof FlowerBedBlock
				|| block instanceof LeafLitterBlock
				|| block instanceof DryVegetationBlock
				|| block instanceof BushBlock
				|| block instanceof FireflyBushBlock
				|| (block instanceof DoublePlantBlock && !(block instanceof PitcherCropBlock));
	}
}
