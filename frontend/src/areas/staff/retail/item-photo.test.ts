import { describe, expect, it } from 'vitest';
import { createMockRetail } from '../../../api/retail-mock';
import { MAX_PHOTO_BYTES, photoProblem, shownPhoto } from './item-photo';

// Item photos (issue #384): a file that cannot be a photo is refused in plain words before it is sent, and an item's photo is
// shown for each of its sizes until a size has its own. Fabricated data.

describe('item photos', () => {
  it('refuses a file that is not a picture or is too large, in plain words', () => {
    expect(photoProblem({ size: 1000, type: 'image/svg+xml' })).toContain('PNG, JPEG or WebP');
    expect(photoProblem({ size: MAX_PHOTO_BYTES + 1, type: 'image/jpeg' })).toContain('larger than 5 MB');
    expect(photoProblem({ size: 1000, type: 'image/webp' })).toBeNull();
  });

  it('shows the item photo for every size without its own', async () => {
    const api = createMockRetail();
    const [category] = await api.listCategories();
    const [unit] = await api.listUnits();
    const family = await api.createFamily({ name: 'Test romper', category_id: category?.id ?? '', unit_id: unit?.id ?? '', cost_minor: 1, sell_minor: 2, sizes: ['S', 'M'] });
    const withPhoto = await api.setFamilyPhoto(family.id ?? '', new Blob(['x'], { type: 'image/png' }));
    expect(withPhoto.variants?.every((v) => v.photo_id === undefined && v.item_photo_id === withPhoto.photo_id)).toBe(true);
    const small = withPhoto.variants?.[0];
    const own = await api.setProductPhoto(small?.id ?? '', new Blob(['y'], { type: 'image/png' }));
    expect(own.photo_id).not.toBe(withPhoto.photo_id);
    expect(own.item_photo_id).toBe(withPhoto.photo_id);
    const back = await api.clearProductPhoto(small?.id ?? '');
    expect(back.photo_id).toBeUndefined();
    expect(shownPhoto(back)).toBe(withPhoto.photo_id);
  });
});
