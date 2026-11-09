export const demoWorld = "demo-overworld";

function village(id, name, color, ownerName, x, z, memberCount, balance, shape, sites) {
  const anchorX = Math.floor(x / 16);
  const anchorZ = Math.floor(z / 16);
  const plots = (left, top, rows) => rows.flatMap((row, dz) =>
    Array.from(row, (cell, dx) => cell === "#" ? {
      world: demoWorld, x: left + dx, z: top + dz,
    } : null).filter(Boolean));
  const capital = plots(anchorX - Math.floor(shape[0].length / 2), anchorZ - Math.floor(shape.length / 2), shape);
  const settlements = sites.map(([structure, cx, cz]) => ({
    structure: "minecraft:village_" + structure,
    bell: { world: demoWorld, x: cx * 16 + 8, y: 68, z: cz * 16 + 8 },
    plots: plots(cx - 2, cz - 1, [".###", "####", "###."]),
  }));
  const claims = capital.concat(settlements.flatMap(site => site.plots));
  return {
    id, name, color, ownerName, memberCount, balance, claims, settlements,
    beacon: { world: demoWorld, x, y: 79, z },
    claimCount: claims.length,
    points: 99 + capital.length + settlements.length * 100,
  };
}

export const demo = {
  schemaVersion: 1,
  worlds: { [demoWorld]: "The Overworld" },
  villages: [
    village("alder", "Alderwatch", "#f1c268", "Rowan", -585, -250, 14, 186000,
      ["..#####..", ".#######.", "#########", "#########", ".########", "..#####..", "....##..."],
      [["plains", -22, -19], ["taiga", -48, -11]]),
    village("stone", "Stonehaven", "#95c9fa", "Flint", 725, -440, 11, 132000,
      ["..####.", ".######", "#######", ".######", "..####.", "...##.."],
      [["meadow", 55, -22], ["taiga", 37, -32]]),
    village("moss", "Mossmere", "#a7d493", "Fern", -420, 400, 8, 78500,
      ["...####", ".######", "#######", "######.", ".####.."],
      [["plains", -35, 28]]),
    village("ember", "Emberfall", "#ee9c85", "Ash", 645, 450, 6, 64000,
      ["...##.", ".#####", "######", "#####.", "..###."],
      [["savanna", 31, 31]]),
    village("grey", "Greyfen", "#c6aceb", "Reed", 20, 90, 5, 32000,
      ["..###", ".####", "#####", "..##."], []),
  ],
};
