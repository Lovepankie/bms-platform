import { type FormEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { encode } from 'uqr';
import type { MfaEnrolment } from '../../api/client';
import { groupSecret, passwordChecks, recoveryCodesText, sixDigits } from '../../auth/codes';

// The pieces of the first-run and sign-in screens (issue #86): a progress line, the password
// fields with a live checklist, the two-step set-up with a QR code drawn in the browser, and the
// recovery codes. Plain words, one main action per screen, 44px targets, labels tied to inputs.
// Nothing here logs or puts a secret, token or code in the address bar.

/** Step N of M, the step's heading (focused when the step opens) and the page title. */
export function StepHeader({ step, of, title, lead }: { step?: number; of?: number; title: string; lead?: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = step && of ? `Step ${step} of ${of}: ${title}` : title;
    heading.current?.focus();
  }, [step, of, title]);
  return (
    <>
      {step && of && (
        <div className="step-progress">
          <p>
            Step {step} of {of}
          </p>
          <ol aria-hidden="true">
            {Array.from({ length: of }, (_, i) => (
              <li key={i} className={i < step ? 'is-done' : undefined} />
            ))}
          </ol>
        </div>
      )}
      <h1 ref={heading} tabIndex={-1}>
        {title}
      </h1>
      {lead && <p className="lead">{lead}</p>}
    </>
  );
}

/** A problem in plain words; it takes focus so a screen reader reads it at once. */
export function ErrorLine({ message }: { message: string | null }) {
  const box = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (message) box.current?.focus();
  }, [message]);
  if (!message) return null;
  return (
    <p ref={box} tabIndex={-1} role="alert" className="alert alert-danger">
      {message}
    </p>
  );
}

/** New password twice, a show or hide switch, and the rules ticked off as the user types. */
export function PasswordFields(props: { password: string; confirm: string; onPassword: (v: string) => void; onConfirm: (v: string) => void }) {
  const [shown, setShown] = useState(false);
  const checks = passwordChecks(props.password, props.confirm);
  const type = shown ? 'text' : 'password';
  return (
    <>
      <div>
        <label htmlFor="new-password">New password</label>
        <div className="password-row">
          <input
            id="new-password"
            type={type}
            value={props.password}
            onChange={(e) => props.onPassword(e.target.value)}
            autoComplete="new-password"
            aria-describedby="password-rules"
            required
          />
          <button type="button" className="btn-sm" aria-pressed={shown} onClick={() => setShown(!shown)}>
            {shown ? 'Hide' : 'Show'}
          </button>
        </div>
      </div>
      <div>
        <label htmlFor="confirm-password">Type it again</label>
        <input id="confirm-password" type={type} value={props.confirm} onChange={(e) => props.onConfirm(e.target.value)} autoComplete="new-password" required />
      </div>
      <ul id="password-rules" className="checklist" aria-label="Password rules">
        {checks.map((c) => (
          <li key={c.label} className={c.ok ? 'is-met' : undefined}>
            <span aria-hidden="true">{c.ok ? '✓' : '•'}</span> {c.label}
            <span className="visually-hidden">{c.ok ? ': done' : ': not yet'}</span>
          </li>
        ))}
        <li>
          <span aria-hidden="true">{'•'}</span> Not a common password, and not your email or phone
        </li>
      </ul>
    </>
  );
}

/** Whether the password fields pass the checks the browser can make. */
export function passwordReady(password: string, confirm: string): boolean {
  return passwordChecks(password, confirm).every((c) => c.ok);
}

/**
 * A QR code of `value`, drawn as one SVG path in the browser (the uqr library, no network). The
 * set-up key never leaves this page.
 */
export function QrCode({ value, label }: { value: string; label: string }) {
  const { d, size } = useMemo(() => {
    const qr = encode(value, { ecc: 'M', border: 2 });
    const parts: string[] = [];
    qr.data.forEach((row, y) =>
      row.forEach((dark, x) => {
        if (dark) parts.push(`M${x} ${y}h1v1h-1z`);
      }),
    );
    return { d: parts.join(''), size: qr.size };
  }, [value]);
  return (
    <svg className="qr-code" viewBox={`0 0 ${size} ${size}`} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect className="qr-light" width={size} height={size} />
      <path className="qr-dark" d={d} />
    </svg>
  );
}

/** Copies text and says so for a moment; quiet if the browser refuses. */
export function useCopy(): [string | null, (key: string, text: string) => void] {
  const [copied, setCopied] = useState<string | null>(null);
  function copy(key: string, text: string) {
    void navigator.clipboard
      ?.writeText(text)
      .then(() => {
        setCopied(key);
        window.setTimeout(() => setCopied(null), 2500);
      })
      .catch(() => undefined);
  }
  return [copied, copy];
}

/**
 * The two-step set-up: what an authenticator app is, the QR code, an Open in authenticator button
 * for a phone that has the app, the key in groups of four with Copy, and the 6 digit code.
 */
export function TwoStepSetup(props: {
  enrolment: MfaEnrolment;
  code: string;
  onCode: (v: string) => void;
  busy: boolean;
  onSubmit: (e: FormEvent) => void;
  children?: ReactNode;
}) {
  const [copied, copy] = useCopy();
  const secret = props.enrolment.secret ?? '';
  return (
    <form onSubmit={props.onSubmit} className="form-stack">
      <p>
        An authenticator app shows a new 6 digit code every 30 seconds. Google Authenticator and Microsoft Authenticator
        are free and work well. Install one on your phone first.
      </p>
      <ol className="steps">
        <li>Open the app and choose to add an account.</li>
        <li>Scan this code with the app. On this phone, tap Open in authenticator instead.</li>
        <li>Type the 6 digit code the app shows.</li>
      </ol>
      <div className="qr-box">
        <QrCode value={props.enrolment.otpauth_uri ?? ''} label="QR code to scan with your authenticator app" />
      </div>
      <a className="btn btn-block" href={props.enrolment.otpauth_uri}>
        Open in authenticator
      </a>
      <details className="manual-key">
        <summary>Cannot scan? Type a key instead</summary>
        <p className="hint">In the app, choose to enter a key, and type this one:</p>
        <div className="secret-box">
          <code aria-label="Key for your authenticator app">{groupSecret(secret)}</code>
          <button type="button" className="btn-sm" onClick={() => copy('key', secret)}>
            {copied === 'key' ? 'Copied' : 'Copy'}
          </button>
        </div>
      </details>
      <div>
        <label htmlFor="totp-code">6 digit code from the app</label>
        <input
          id="totp-code"
          className="input-code"
          inputMode="numeric"
          pattern="[0-9]{6}"
          maxLength={6}
          autoComplete="one-time-code"
          value={props.code}
          onChange={(e) => props.onCode(sixDigits(e.target.value))}
          required
        />
      </div>
      <button className="btn-primary btn-lg" disabled={props.busy || props.code.length !== 6}>
        {props.busy ? 'Checking' : 'Turn on two-step sign-in'}
      </button>
      {props.children}
    </form>
  );
}

/** Ten one-use codes, shown once: copy, download, and a tick before going on. */
export function RecoveryCodes({ codes, onDone, doneLabel = 'Continue' }: { codes: string[]; onDone: () => void; doneLabel?: string }) {
  const [saved, setSaved] = useState(false);
  const [copied, copy] = useCopy();

  function download() {
    const text = `Recovery codes. Each one signs you in once if you lose your phone.\n\n${recoveryCodesText(codes)}\n`;
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'recovery-codes.txt';
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <section className="form-stack" aria-label="Recovery codes">
      <p>
        If you lose your phone, each of these codes lets you sign in once. Keep them somewhere safe, away from your phone.
        You will not see them again.
      </p>
      <ol className="codes-grid">
        {codes.map((c) => (
          <li key={c}>
            <code>{c}</code>
          </li>
        ))}
      </ol>
      <div className="button-pair">
        <button type="button" onClick={() => copy('codes', recoveryCodesText(codes))}>
          {copied === 'codes' ? 'Copied' : 'Copy all'}
        </button>
        <button type="button" onClick={download}>
          Download as text
        </button>
      </div>
      <label>
        <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} />I have saved these codes
      </label>
      <button type="button" className="btn-primary btn-lg" disabled={!saved} onClick={onDone}>
        {doneLabel}
      </button>
    </section>
  );
}
