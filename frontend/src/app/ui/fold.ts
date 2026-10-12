// The folding table of the search, equal to bms_fold in migration V40 (a test compares the two).
// Lower case, Latin accents removed, sharp s, ae and oe spelled out.

export const FOLD_FROM =
  'àÀáÁâÂãÃäÄåÅāĀăĂąĄçÇćĆčČďĎđĐèÈéÉêÊëËēĒėĖęĘěĚğĞìÌíÍîÎïÏīĪįĮıIłŁľĽñÑńŃňŇòÒóÓôÔõÕöÖøØōŌőŐŕŔřŘśŚšŠşŞșȘťŤţŢțȚùÙúÚûÛüÜūŪůŮűŰųŲýÝÿŸźŹżŻžŽ';
export const FOLD_TO = 'aaaaaaaaaaaaaaaaaaccccccddddeeeeeeeeeeeeeeeeggiiiiiiiiiiiiiillllnnnnnnoooooooooooooooorrrrssssssssttttttuuuuuuuuuuuuuuuuyyyyzzzzzz';

const TABLE = new Map<string, string>(Array.from(FOLD_FROM).map((c, i) => [c, FOLD_TO[i] ?? c]));

/** The ligatures the database spells out before the table: sharp s, ae and oe, in both cases. */
export const FOLD_LIGATURES: [string, string][] = [
  ['\u00df', 'ss'],
  ['\u1e9e', 'ss'],
  ['\u00e6', 'ae'],
  ['\u00c6', 'ae'],
  ['\u0153', 'oe'],
  ['\u0152', 'oe'],
];

/**
 * The text as the database folds it for a search (`bms_fold`): ligatures spelled out, Latin accents
 * removed by the same table, lower case. Text typed with a combining accent is first made one letter
 * (NFC), as the box does before it sends.
 */
export function foldText(text: string): string {
  let t = text.normalize('NFC');
  for (const [from, to] of FOLD_LIGATURES) t = t.split(from).join(to);
  return Array.from(t, (c) => TABLE.get(c) ?? c)
    .join('')
    .toLowerCase();
}
