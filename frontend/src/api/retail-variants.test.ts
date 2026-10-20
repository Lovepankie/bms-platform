import { describe, expect, it } from 'vitest';
import { codeToken, planVariants, repeatedCodes, splitOptions, variantCode, variantDescription, variantKey, variantLabel } from './retail-variants';

// The quick creation of sizes and colours (issue #384): the same combinations, codes and order as the server's VariantGrid.

describe('sizes and colours chosen once', () => {
  it('makes every combination, sizes first in the order given, at the item prices', () => {
    const plan = planVariants({ baseCode: 'ROMP', sizes: ['0-3m', '3-6m', '6-12m'], colours: ['Blue', 'Pink'], costMinor: 4000, sellMinor: 9000 });
    expect(plan.map((p) => p.code)).toEqual(['ROMP-03M-BLUE', 'ROMP-03M-PINK', 'ROMP-36M-BLUE', 'ROMP-36M-PINK', 'ROMP-612M-BLUE', 'ROMP-612M-PINK']);
    expect(plan.map((p) => p.position)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(plan.every((p) => p.sellMinor === 9000 && p.costMinor === 4000)).toBe(true);
  });

  it('makes one per size or one per colour when the other list is empty, and nothing from blanks', () => {
    expect(planVariants({ baseCode: 'CAB', sizes: ['1.5mm', '2.5mm'], colours: [], costMinor: 1, sellMinor: 2 }).map((p) => p.code)).toEqual(['CAB-15MM', 'CAB-25MM']);
    expect(planVariants({ baseCode: 'SOCK', sizes: [], colours: ['Red', ' red ', 'White'], costMinor: 1, sellMinor: 2 }).map((p) => p.colour)).toEqual(['Red', 'White']);
    expect(planVariants({ baseCode: 'X', sizes: [' '], colours: [], costMinor: 1, sellMinor: 2 })).toEqual([]);
  });

  it('skips a combination the item has and continues its positions', () => {
    const plan = planVariants({ baseCode: 'ROMP', sizes: ['0-3m'], colours: ['blue', 'Pink'], costMinor: 1, sellMinor: 2, existing: new Set([variantKey('0-3m', 'Blue')]), nextPosition: 3 });
    expect(plan.map((p) => p.colour)).toEqual(['Pink']);
    expect(plan[0]?.position).toBe(3);
  });

  it('finds codes two variants would share', () => {
    const plan = planVariants({ baseCode: 'ROMP', sizes: ['0-3m', '03m'], colours: [], costMinor: 1, sellMinor: 2 });
    expect(repeatedCodes(plan)).toEqual(['ROMP-03M']);
  });

  it('reads a typed list and names a variant in plain words', () => {
    expect(splitOptions('0-3m, 3-6m;6-12m\n0-3M')).toEqual(['0-3m', '3-6m', '6-12m']);
    expect(variantDescription('Baby romper', '0-3m', 'Blue')).toBe('Baby romper (0-3m, Blue)');
    expect(variantLabel(undefined, 'White')).toBe('White');
    expect(variantCode('ROMP', '0-3m')).toBe('ROMP-03M');
    expect(codeToken('Sky blue stripes')).toBe('SKYBLUE');
  });
});
