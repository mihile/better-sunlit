// JEI & EMI Fishing Conditions and Probabilities for Sunlit Valley
const FISHING_SEASONS = [
  { key: "spring", color: "darkGreen", labelKey: "desc.sereneseasons.spring" },
  { key: "summer", color: "gold", labelKey: "desc.sereneseasons.summer" },
  { key: "autumn", color: "gold", labelKey: "desc.sereneseasons.autumn" },
  { key: "winter", color: "darkAqua", labelKey: "desc.sereneseasons.winter" },
];

const FISHING_REGIONS = [
  { key: "Ocean", labelKey: "society.fish_radar.ocean", noteKey: "jei.better_sunlit.fishing.region.ocean_note" },
  { key: "River", labelKey: "society.fish_radar.river", noteKey: "jei.better_sunlit.fishing.region.river_note" },
  { key: "Fresh", labelKey: "society.fish_radar.fresh", noteKey: "jei.better_sunlit.fishing.region.fresh_note" },
];

const FISHING_SUBPOOLS = [
  { night: false, rain: false, timeKey: "society.fish_radar.day", weatherKey: "society.fish_radar.clear" },
  { night: false, rain: true, timeKey: "society.fish_radar.day", weatherKey: "society.fish_radar.rain" },
  { night: true, rain: false, timeKey: "society.fish_radar.night", weatherKey: "society.fish_radar.clear" },
  { night: true, rain: true, timeKey: "society.fish_radar.night", weatherKey: "society.fish_radar.rain" },
];

const NETHER_FISH_ENTRIES = [
  { item: "netherdepthsupgrade:searing_cod", weight: 60, biomeKey: "society.fish_radar.nether" },
  { item: "netherdepthsupgrade:glowdine", weight: 25, biomeKey: "biome.minecraft.warped_forest" },
  { item: "netherdepthsupgrade:bonefish", weight: 15, biomeKey: "biome.minecraft.nether_wastes" },
  { item: "netherdepthsupgrade:blazefish", weight: 15, biomeKey: "society.fish_radar.nether" },
  { item: "netherdepthsupgrade:lava_pufferfish", weight: 13, biomeKey: "society.fish_radar.nether" },
  { item: "netherdepthsupgrade:obsidianfish", weight: 13, biomeKey: "biome.minecraft.basalt_deltas" },
  { item: "netherdepthsupgrade:eyeball_fish", weight: 10, biomeKey: "biome.minecraft.crimson_forest" },
  { item: "netherdepthsupgrade:wither_bonefish", weight: 2, biomeKey: "biome.minecraft.soul_sand_valley" },
  { item: "netherdepthsupgrade:magmacubefish", weight: 2, biomeKey: "biome.minecraft.basalt_deltas" },
  { item: "netherdepthsupgrade:soulsucker", weight: 1, biomeKey: "biome.minecraft.soul_sand_valley" },
  { item: "netherdepthsupgrade:fortress_grouper", weight: 1, biomeKey: "society.fish_radar.nether" },
];

const NETHER_TOTAL_WEIGHT = 157;

const SPECIAL_FISHING_ENTRIES = [
  {
    item: "society:ocean_jelly",
    chance: "10%",
    regionKey: "society.fish_radar.ocean",
    stageQuest: true,
    bonusHook: "aquaculture:redstone_hook",
    bonusChance: "10% (1-2x)",
  },
  {
    item: "society:river_jelly",
    chance: "10%",
    regionKey: "society.fish_radar.river",
    stageQuest: true,
    bonusHook: "aquaculture:redstone_hook",
    bonusChance: "10% (1-2x)",
  },
  {
    item: "society:furniture_box",
    chance: "10%",
    regionKey: "jei.better_sunlit.fishing.region.all",
    stageKey: "society.skills.fishing.wooden_pollution",
  },
  {
    item: "aquaculture:treasure_chest",
    chance: "45%",
    seasonKey: "desc.sereneseasons.winter",
    regionKey: "jei.better_sunlit.fishing.region.all",
    stageKey: "society.skills.fishing.frozen_treasure",
  },
  {
    item: "crabbersdelight:crab_trap_bait",
    chance: "50%",
    amount: "4-16",
    hook: "aquaculture:iron_hook",
    regionKey: "jei.better_sunlit.fishing.region.all",
  },
  {
    item: "crabbersdelight:deluxe_crab_trap_bait",
    chance: "25%",
    amount: "1-4",
    hook: "aquaculture:diamond_hook",
    regionKey: "jei.better_sunlit.fishing.region.all",
  },
  {
    item: "society:relic_trove",
    chance: "4%",
    hook: "aquaculture:nether_star_hook",
    regionKey: "jei.better_sunlit.fishing.region.all",
  },
  {
    item: "society:animal_cracker",
    chance: "1%",
    regionKey: "jei.better_sunlit.fishing.region.all",
    stageKey: "society_skills.mastery.husbandry_mastery",
  },
  {
    item: "society:plushie_capsule",
    chance: "5%",
    regionKey: "jei.better_sunlit.fishing.region.all",
    stageKey: "society_skills.mastery.husbandry_mastery",
  },
];

function formatFishingPct(value) {
  return (Math.round(value * 10) / 10).toFixed(1);
}

function colorFishingText(text, colorName) {
  switch (colorName) {
    case "darkGreen":
      return text.darkGreen();
    case "gold":
      return text.gold();
    case "darkAqua":
      return text.darkAqua();
    case "blue":
      return text.blue();
    case "darkPurple":
      return text.darkPurple();
    default:
      return text;
  }
}

function summarizeOverworldFish() {
  let map = {};
  FISHING_SEASONS.forEach((season) => {
    let capSeason = season.key;
    FISHING_REGIONS.forEach((region) => {
      let globalKey = `${capSeason}${region.key}`;
      let entries = global[globalKey];
      if (!entries) return;

      let subMatchesByFish = {};
      FISHING_SUBPOOLS.forEach((sp) => {
        let active = entries.filter((entry) => {
          let isNight = entry.night === true;
          if (isNight !== sp.night) return false;
          if (entry.requiresRain && !sp.rain) return false;
          if (entry.requiresClear && sp.rain) return false;
          return true;
        });
        let totalWeight = 0;
        active.forEach((entry) => {
          totalWeight += entry.weight;
        });
        if (totalWeight <= 0) return;

        let weightPerFish = {};
        active.forEach((entry) => {
          weightPerFish[entry.fish] = (weightPerFish[entry.fish] || 0) + entry.weight;
        });
        Object.keys(weightPerFish).forEach((fishId) => {
          let w = weightPerFish[fishId];
          let pct = (100 * w) / totalWeight;
          if (!subMatchesByFish[fishId]) subMatchesByFish[fishId] = [];
          subMatchesByFish[fishId].push({
            seasonKey: season.key,
            seasonLabelKey: season.labelKey,
            seasonColor: season.color,
            regionLabelKey: region.labelKey,
            regionNoteKey: region.noteKey,
            timeKey: sp.timeKey,
            weatherKey: sp.weatherKey,
            night: sp.night,
            rain: sp.rain,
            weight: w,
            totalWeight: totalWeight,
            pct: pct,
          });
        });
      });

      Object.keys(subMatchesByFish).forEach((fishId) => {
        let subs = subMatchesByFish[fishId];
        let hasDay = false;
        let hasNight = false;
        let hasClear = false;
        let hasRain = false;
        let minPct = 100;
        let maxPct = 0;
        subs.forEach((s) => {
          if (s.night) hasNight = true;
          else hasDay = true;
          if (s.rain) hasRain = true;
          else hasClear = true;
          if (s.pct < minPct) minPct = s.pct;
          if (s.pct > maxPct) maxPct = s.pct;
        });

        let timeLabelKey =
          hasDay && hasNight
            ? "jei.better_sunlit.fishing.time.all"
            : hasNight
              ? "society.fish_radar.night"
              : "society.fish_radar.day";
        let weatherLabelKey =
          hasClear && hasRain
            ? "jei.better_sunlit.fishing.weather.any"
            : hasRain
              ? "society.fish_radar.rain"
              : "society.fish_radar.clear";
        let pctStr =
          Math.abs(maxPct - minPct) < 0.05
            ? `${formatFishingPct(minPct)}%`
            : `${formatFishingPct(minPct)}~${formatFishingPct(maxPct)}%`;

        if (!map[fishId]) {
          map[fishId] = {
            kind: "overworld",
            item: fishId,
            summaries: [],
            details: [],
          };
        }
        map[fishId].summaries.push({
          seasonLabelKey: season.labelKey,
          seasonColor: season.color,
          regionLabelKey: region.labelKey,
          regionNoteKey: region.noteKey,
          timeLabelKey: timeLabelKey,
          weatherLabelKey: weatherLabelKey,
          pctText: pctStr,
        });
        subs.forEach((s) => map[fishId].details.push(s));
      });
    });
  });
  return Object.keys(map).map((k) => map[k]);
}

function drawFishingText(guiGraphics, text, x, y, maxWidth, defaultColor) {
  guiGraphics.drawWordWrap(Client.font, text, x, y, maxWidth, defaultColor || 0x202020);
}

function buildOverworldRowComponent(row) {
  return Text.translatable(
    "jei.better_sunlit.fishing.row_line",
    colorFishingText(Text.translatable(row.seasonLabelKey), row.seasonColor),
    Text.translatable(row.regionLabelKey).darkBlue(),
    Text.translatable(row.timeLabelKey),
    Text.translatable(row.weatherLabelKey),
    Text.of(row.pctText).darkPurple()
  );
}

function renderFishingCard(data, guiGraphics) {
  let typeKey =
    data.kind === "nether"
      ? "jei.better_sunlit.fishing.type.nether"
      : data.kind === "special"
        ? "jei.better_sunlit.fishing.type.special"
        : "jei.better_sunlit.fishing.type.overworld";

  let hasSecondCatalyst = !!(data.hook || data.bonusHook);
  let headerX = hasSecondCatalyst ? 43 : 24;
  let headerW = hasSecondCatalyst ? 110 : 129;

  drawFishingText(guiGraphics, Text.translatable(typeKey).darkGray(), headerX, 2, headerW, 0x404040);
  drawFishingText(guiGraphics, Item.of(data.item).hoverName, headerX, 12, headerW, 0x101010);

  if (data.kind === "overworld") {
    let rows = data.summaries || [];
    let y = 28;
    rows.forEach((row, idx) => {
      if (idx < 8) {
        drawFishingText(guiGraphics, buildOverworldRowComponent(row), 3, y, 170, 0x202020);
        y += 10;
      }
    });
    if (rows.length <= 2) {
      let details = data.details || [];
      y += 4;
      details.forEach((d, idx) => {
        if (y <= 106 && idx < 5) {
          let pctLabel = Text.translatable(
            "jei.better_sunlit.fishing.weight_fmt",
            formatFishingPct(d.pct),
            `${d.weight}`,
            `${d.totalWeight}`
          ).darkPurple();
          let detailLine = Text.translatable(
            "jei.better_sunlit.fishing.detail_line",
            colorFishingText(Text.translatable(d.seasonLabelKey), d.seasonColor),
            Text.translatable(d.regionLabelKey).darkBlue(),
            Text.translatable(d.timeKey),
            Text.translatable(d.weatherKey),
            pctLabel
          );
          drawFishingText(guiGraphics, detailLine, 4, y, 168, 0x303030);
          y += 10;
        }
      });
    }
    drawFishingText(
      guiGraphics,
      Text.translatable("jei.better_sunlit.fishing.hover_hint").darkGray(),
      3,
      118,
      170,
      0x555555
    );
    return;
  }

  if (data.kind === "neptuna") {
    drawFishingText(
      guiGraphics,
      Text.translatable(
        "jei.better_sunlit.fishing.row_line",
        Text.translatable("desc.sereneseasons.year_round").darkPurple(),
        Text.translatable("jei.better_sunlit.fishing.region.all").darkBlue(),
        Text.translatable("jei.better_sunlit.fishing.time.all"),
        Text.translatable("jei.better_sunlit.fishing.weather.any"),
        Text.of("~8.3%").darkPurple()
      ),
      3,
      30,
      170,
      0x202020
    );
    drawFishingText(
      guiGraphics,
      Text.translatable(
        "jei.better_sunlit.fishing.neptuna.skill",
        Text.translatable("society.skills.fishing.mystical_ocean").gold()
      ),
      3,
      46,
      170,
      0x202020
    );
    drawFishingText(
      guiGraphics,
      Text.translatable("jei.better_sunlit.fishing.neptuna.chance").darkGreen(),
      3,
      60,
      170,
      0x202020
    );
    return;
  }

  if (data.kind === "nether") {
    drawFishingText(
      guiGraphics,
      Text.translatable("jei.better_sunlit.fishing.nether.rod").gold(),
      3,
      30,
      170,
      0x202020
    );
    drawFishingText(
      guiGraphics,
      Text.translatable(
        "jei.better_sunlit.fishing.nether.pool",
        formatFishingPct((100 * data.weight) / NETHER_TOTAL_WEIGHT),
        `${data.weight}`
      ).darkPurple(),
      3,
      44,
      170,
      0x202020
    );
    drawFishingText(
      guiGraphics,
      Text.translatable(
        "jei.better_sunlit.fishing.nether.radar_hint",
        Text.translatable(data.biomeKey).darkBlue()
      ),
      3,
      58,
      170,
      0x202020
    );
    drawFishingText(
      guiGraphics,
      Text.translatable("jei.better_sunlit.fishing.nether.junk_note").darkGray(),
      3,
      78,
      170,
      0x555555
    );
    return;
  }

  if (data.kind === "special") {
    let sy = 28;
    drawFishingText(
      guiGraphics,
      Text.translatable("jei.better_sunlit.fishing.special.chance", Text.of(data.chance).darkPurple()),
      3,
      sy,
      170,
      0x202020
    );
    sy += 12;
    if (data.amount) {
      drawFishingText(
        guiGraphics,
        Text.translatable("jei.better_sunlit.fishing.special.amount", Text.of(data.amount).darkGreen()),
        3,
        sy,
        170,
        0x202020
      );
      sy += 12;
    }
    if (data.seasonKey) {
      drawFishingText(
        guiGraphics,
        Text.translatable(
          "jei.better_sunlit.fishing.special.season",
          Text.translatable(data.seasonKey).darkAqua()
        ),
        3,
        sy,
        170,
        0x202020
      );
      sy += 12;
    }
    drawFishingText(
      guiGraphics,
      Text.translatable(
        "jei.better_sunlit.fishing.special.region",
        Text.translatable(data.regionKey).darkBlue()
      ),
      3,
      sy,
      170,
      0x202020
    );
    sy += 12;
    if (data.hook) {
      drawFishingText(
        guiGraphics,
        Text.translatable("jei.better_sunlit.fishing.special.hook", Item.of(data.hook).hoverName),
        3,
        sy,
        170,
        0x202020
      );
      sy += 14;
    }
    if (data.bonusHook) {
      drawFishingText(
        guiGraphics,
        Text.translatable(
          "jei.better_sunlit.fishing.special.bonus_hook",
          Item.of(data.bonusHook).hoverName,
          data.bonusChance
        ).darkGreen(),
        3,
        sy,
        170,
        0x202020
      );
      sy += 14;
    }
    if (data.stageKey) {
      drawFishingText(
        guiGraphics,
        Text.translatable(
          "jei.better_sunlit.fishing.special.stage",
          Text.translatable(data.stageKey).darkPurple()
        ),
        3,
        sy,
        170,
        0x202020
      );
    } else if (data.stageQuest) {
      drawFishingText(
        guiGraphics,
        Text.translatable(
          "jei.better_sunlit.fishing.special.stage",
          Text.translatable("jei.better_sunlit.fishing.special.quest_stage").darkPurple()
        ),
        3,
        sy,
        170,
        0x202020
      );
    }
  }
}

function buildFishingTooltipLines(data) {
  let lines = [];
  if (data.kind === "overworld") {
    let seenNotes = {};
    data.summaries.forEach((s) => {
      if (!seenNotes[s.regionNoteKey]) {
        seenNotes[s.regionNoteKey] = true;
        lines.push(Text.translatable(s.regionNoteKey).aqua());
      }
    });
    data.details.forEach((d) => {
      let pctLabel = Text.translatable(
        "jei.better_sunlit.fishing.weight_fmt",
        formatFishingPct(d.pct),
        `${d.weight}`,
        `${d.totalWeight}`
      ).gold();
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.detail_line",
          Text.translatable(d.seasonLabelKey).green(),
          Text.translatable(d.regionLabelKey).aqua(),
          Text.translatable(d.timeKey).yellow(),
          Text.translatable(d.weatherKey).blue(),
          pctLabel
        )
      );
    });
    lines.push(Text.translatable("jei.better_sunlit.fishing.overworld.note").gray());
  } else if (data.kind === "neptuna") {
    lines.push(
      Text.translatable(
        "jei.better_sunlit.fishing.neptuna.skill",
        Text.translatable("society.skills.fishing.mystical_ocean").gold()
      )
    );
    lines.push(Text.translatable("jei.better_sunlit.fishing.neptuna.chance").green());
  } else if (data.kind === "nether") {
    lines.push(Text.translatable("jei.better_sunlit.fishing.nether.rod").gold());
    lines.push(
      Text.translatable(
        "jei.better_sunlit.fishing.nether.pool",
        formatFishingPct((100 * data.weight) / NETHER_TOTAL_WEIGHT),
        `${data.weight}`
      ).yellow()
    );
    lines.push(
      Text.translatable(
        "jei.better_sunlit.fishing.nether.radar_hint",
        Text.translatable(data.biomeKey).aqua()
      )
    );
    lines.push(Text.translatable("jei.better_sunlit.fishing.nether.junk_note").gray());
  } else if (data.kind === "special") {
    lines.push(
      Text.translatable("jei.better_sunlit.fishing.special.chance", Text.of(data.chance).gold())
    );
    if (data.amount) {
      lines.push(
        Text.translatable("jei.better_sunlit.fishing.special.amount", Text.of(data.amount).green())
      );
    }
    if (data.seasonKey) {
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.special.season",
          Text.translatable(data.seasonKey).aqua()
        )
      );
    }
    lines.push(
      Text.translatable(
        "jei.better_sunlit.fishing.special.region",
        Text.translatable(data.regionKey).blue()
      )
    );
    if (data.hook) {
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.special.hook",
          Item.of(data.hook).hoverName
        ).yellow()
      );
    }
    if (data.bonusHook) {
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.special.bonus_hook",
          Item.of(data.bonusHook).hoverName,
          data.bonusChance
        ).green()
      );
    }
    if (data.stageKey) {
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.special.stage",
          Text.translatable(data.stageKey).lightPurple()
        )
      );
    } else if (data.stageQuest) {
      lines.push(
        Text.translatable(
          "jei.better_sunlit.fishing.special.stage",
          Text.translatable("jei.better_sunlit.fishing.special.quest_stage").lightPurple()
        )
      );
    }
  }
  return lines;
}

JEIAddedEvents.registerCategories((e) => {
  let guiHelper = e.data.jeiHelpers.guiHelper;
  e.custom("society:fishing", (category) => {
    category
      .title(Text.translatable("jei.better_sunlit.fishing.category"))
      .background(guiHelper.createBlankDrawable(176, 130))
      .icon(guiHelper.createDrawableItemStack(Item.of("minecraft:fishing_rod")))
      .isRecipeHandled((recipe) => {
        return !!(recipe?.data?.item !== undefined && recipe?.data?.kind !== undefined);
      })
      .setDrawHandler((recipe, recipeSlotsView, guiGraphics) => {
        renderFishingCard(recipe.getRecipeData(), guiGraphics);
      })
      .handleLookup((builder, recipe) => {
        let data = recipe.data;
        if (data.kind === "nether") {
          builder
            .addSlot("CATALYST", 2, 2)
            .addItemStack(Item.of("netherdepthsupgrade:lava_fishing_rod"))
            .setBackground(guiHelper.getSlotDrawable(), -1, -1);
        } else {
          builder
            .addSlot("CATALYST", 2, 2)
            .addIngredients([Ingredient.of("#forge:tools/fishing_rods")])
            .setBackground(guiHelper.getSlotDrawable(), -1, -1);
        }

        let hookCatalyst = data.hook || data.bonusHook;
        if (hookCatalyst) {
          builder
            .addSlot("CATALYST", 22, 2)
            .addItemStack(Item.of(hookCatalyst))
            .setBackground(guiHelper.getSlotDrawable(), -1, -1);
        }

        builder
          .addSlot("OUTPUT", 156, 2)
          .addItemStack(Item.of(data.item))
          .addTooltipCallback((slotView, tooltip) => {
            let extraLines = buildFishingTooltipLines(data);
            extraLines.forEach((line, idx) => {
              tooltip.add(idx + 1, line);
            });
          })
          .setBackground(guiHelper.getSlotDrawable(), -1, -1);
      });
  });
});

JEIAddedEvents.registerRecipes((e) => {
  summarizeOverworldFish().forEach((entry) => {
    e.custom("society:fishing").add(entry);
  });

  e.custom("society:fishing").add({
    kind: "neptuna",
    item: "society:neptuna",
  });

  NETHER_FISH_ENTRIES.forEach((entry) => {
    e.custom("society:fishing").add({
      kind: "nether",
      item: entry.item,
      weight: entry.weight,
      biomeKey: entry.biomeKey,
    });
  });

  SPECIAL_FISHING_ENTRIES.forEach((entry) => {
    e.custom("society:fishing").add({
      kind: "special",
      item: entry.item,
      chance: entry.chance,
      amount: entry.amount,
      seasonKey: entry.seasonKey,
      regionKey: entry.regionKey,
      hook: entry.hook,
      bonusHook: entry.bonusHook,
      bonusChance: entry.bonusChance,
      stageKey: entry.stageKey,
      stageQuest: entry.stageQuest,
    });
  });
});
