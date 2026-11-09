// A deterministic, illustrated demo basemap. The coordinates are in game blocks.
export const terrainBounds = { x: -2304, z: -1536, width: 4608, height: 3072 };
export const atlasLabels = [
  [-1050, -720, "ALDER WOODS"], [1120, -740, "STONEBACK RIDGE"],
  [-1140, 30, "WESTWATER"], [-110, 640, "MOSSMERE BASIN"],
  [1060, 675, "EMBER LOWLANDS"],
];

function hash(x, z) {
  let n = Math.imul(x, 374761393) + Math.imul(z, 668265263);
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
}

function noise(x, z) {
  const ix = Math.floor(x);
  const iz = Math.floor(z);
  const u = x - ix;
  const v = z - iz;
  const a = u * u * (3 - 2 * u);
  const b = v * v * (3 - 2 * v);
  const top = hash(ix, iz) * (1 - a) + hash(ix + 1, iz) * a;
  const bottom = hash(ix, iz + 1) * (1 - a) + hash(ix + 1, iz + 1) * a;
  return top * (1 - b) + bottom * b;
}

function height(x, z) {
  const hills = noise(x / 600 + 40, z / 600 + 35) * .56
    + noise(x / 240 - 10, z / 240) * .26
    + noise(x / 91, z / 91) * .13 + noise(x / 34, z / 34) * .05;
  const ridge = Math.exp(-(((x - 1000) / 640) ** 2 + ((z + 660) / 355) ** 2));
  const coast = -1430 + 185 * Math.sin(z / 274) + 110 * Math.sin(z / 137);
  const westwater = Math.exp(-(((x + 1120) / 450) ** 2 + ((z - 12) / 275) ** 2));
  const basin = Math.exp(-(((x + 5) / 312) ** 2 + ((z - 680) / 225) ** 2));
  const riverX = -130 + 230 * Math.sin((z + 210) / 360) + 80 * Math.sin(z / 135);
  const river = Math.max(0, 1 - Math.abs(x - riverX) / 81);
  const edge = Math.max(-.85, Math.min(.2, (x - coast) / 460));
  return hills + edge + ridge * .52 - westwater * .75 - basin * .69 - river * .54;
}

export function makeTerrain() {
  const canvas = document.createElement("canvas");
  const step = 6;
  canvas.width = terrainBounds.width / step;
  canvas.height = terrainBounds.height / step;
  const context = canvas.getContext("2d");
  const image = context.createImageData(canvas.width, canvas.height);
  const elevations = new Float32Array(canvas.width * canvas.height);
  for (let j = 0; j < canvas.height; j++) {
    for (let i = 0; i < canvas.width; i++) {
      elevations[j * canvas.width + i] = height(terrainBounds.x + i * step, terrainBounds.z + j * step);
    }
  }
  for (let j = 0; j < canvas.height; j++) {
    for (let i = 0; i < canvas.width; i++) {
      const index = j * canvas.width + i;
      const elevation = elevations[index];
      const light = (elevations[index - (j > 0 ? canvas.width : 0)]
        + elevations[index - (i > 0 ? 1 : 0)] - 2 * elevation) * 620;
      const grain = (hash(i + 2000, j - 100) - .5) * 14;
      const canopy = noise(i / 21 + 30, j / 21) * .65 + noise(i / 6, j / 6) * .35;
      let color;
      let shade = Math.max(-31, Math.min(29, light)) + grain;
      if (elevation < .33) {
        color = elevation > .27 ? [63, 105, 100] : [38, 72, 77];
        shade = grain * .45 + Math.max(0, elevation) * 32 + Math.sin(j * .9 + i * .23) * 1.5;
      } else if (elevation < .365) {
        color = [142, 132, 91];
      } else if (elevation > 1.13) {
        color = [168, 168, 149];
      } else if (elevation > .98) {
        color = [126, 129, 107];
      } else if (canopy > .52) {
        color = [63, 88, 60];
        shade -= Math.max(0, canopy - .52) * 36;
        if (hash(i, j) > .8) shade -= 12;
      } else {
        color = [99, 113, 72];
        shade += (.52 - canopy) * 42;
      }
      if (elevation >= .365 && Math.floor(elevation * 42) !==
          Math.floor(elevations[index - (j > 0 ? canvas.width : 0)] * 42)) shade -= 7;
      const pixel = index * 4;
      image.data[pixel] = color[0] + shade;
      image.data[pixel + 1] = color[1] + shade;
      image.data[pixel + 2] = color[2] + shade;
      image.data[pixel + 3] = 255;
    }
  }
  context.putImageData(image, 0, 0);
  return canvas;
}
