import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { openInNewTab } from '../../../api/lending-forms';
import { useMembersApi } from '../../../api/lending-context';
import type { DocKind } from '../../../api/members';
import { Problem } from '../retail/ui';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';
import { BusyButton } from '../../../app/ui/BusyButton';

// A member's documents (FR-MEM-09): the ID photos, a portrait and anything else. Each upload is checked and shrunk on the phone,
// sent with a progress bar and can be sent again. The server lists ID images only to those who may check the details; others
// see the other documents and a line saying so. A file opens through a signed address that lasts five minutes.

const KINDS: { value: DocKind; label: string }[] = [
  { value: 'id_front', label: 'Front of the ID' },
  { value: 'id_back', label: 'Back of the ID' },
  { value: 'photo', label: 'Photo of the member' },
  { value: 'other', label: 'Another document' },
];
const kindWords = (k: string | undefined) => KINDS.find((x) => x.value === k)?.label ?? 'Document';

function OpenButton({ documentId, label }: { documentId: string; label: string }) {
  const api = useMembersApi();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const open = async () => {
    // toast-exempt: it only opens the file in a new tab and saves nothing
    setBusy(true);
    setError(null);
    try {
      await openInNewTab(() => api.documentUrl(documentId));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The file could not be opened.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <BusyButton busy={busy} type="button" className="btn-sm" onClick={() => void open()} aria-label={`Open ${label}`}>Open</BusyButton>
      {error && <span role="alert" className="field-error">{error}</span>}
    </>
  );
}

export function DocumentsSection({ memberId, canAdd, seesIds }: { memberId: string; canAdd: boolean; seesIds: boolean }) {
  const api = useMembersApi();
  const queryClient = useQueryClient();
  const docs = useQuery({ queryKey: ['members', 'documents', memberId], queryFn: () => api.listDocuments(memberId) });
  const [kind, setKind] = useState<DocKind>('id_front');
  const queue = usePhotoQueue();
  const sender: Sender<unknown> = async (p, o) => {
    const out = await api.uploadDocument(memberId, kind, p.blob, p.fileName, o);
    void queryClient.invalidateQueries({ queryKey: ['members', 'documents', memberId] });
    void queryClient.invalidateQueries({ queryKey: ['members', 'one', memberId] });
    return out;
  };
  const items = docs.data ?? [];
  return (
    <section className="rt-card stack" aria-labelledby="docs-title" data-tour="member-documents">
      <h2 id="docs-title">Documents and photos</h2>
      {docs.isPending && <p className="loading">Loading documents</p>}
      <Problem error={docs.error} />
      {docs.data && items.length === 0 && <p className="empty-state">No documents yet.</p>}
      {docs.data && !seesIds && <p className="hint">Photos of the ID are shown only to staff who check member details.</p>}
      <ul className="ln-list">
        {items.map((d) => (
          <li key={d.document_id} className="rt-card cluster">
            <span>{kindWords(d.doc_kind)}</span>
            <span className="hint">{d.created_at ? d.created_at.slice(0, 10) : ''}</span>
            <OpenButton documentId={d.document_id ?? ''} label={kindWords(d.doc_kind)} />
          </li>
        ))}
      </ul>
      {canAdd && (
        <div className="stack">
          <label htmlFor="doc-kind">What is it</label>
          <select id="doc-kind" value={kind} onChange={(e) => setKind(e.target.value as DocKind)}>
            {KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
          </select>
          <PhotoQueueControl id="doc-add" label="Add a photo or scan" queue={queue} sender={sender} max={5} tourId="member-doc-add"
            hint="Photos are shrunk before they are sent. A PDF can be up to 5 MB." />
        </div>
      )}
    </section>
  );
}
