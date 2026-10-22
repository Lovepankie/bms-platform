import { describe, expect, it } from 'vitest';
import { cameraProblem } from './camera-scan';

// The camera's refusals in plain words (ADR-070, issue #345): what happened and what to do next.

describe('FR-RET-68 camera problems', () => {
  it('says how to allow a blocked camera', () => {
    expect(cameraProblem(new DOMException('denied', 'NotAllowedError'))).toContain('Allow the camera for this site');
  });

  it('names a missing camera, a busy camera and an insecure address', () => {
    expect(cameraProblem({ name: 'NotFoundError' })).toContain('no camera');
    expect(cameraProblem({ name: 'NotReadableError' })).toContain('Another app is using the camera');
    expect(cameraProblem({ name: 'SecurityError' })).toContain('https');
    expect(cameraProblem(new Error('odd'))).toContain('Type the barcode instead');
  });

  it('gives each refusal the browser may name its own plain message', () => {
    expect(cameraProblem({ name: 'PermissionDeniedError' })).toContain('Allow the camera for this site');
    expect(cameraProblem({ name: 'OverconstrainedError' })).toBe('This device has no camera the app can use. Use a scanner or type the barcode instead.');
    expect(cameraProblem({ name: 'AbortError' })).toBe('Another app is using the camera. Close it and tap Scan again.');
    expect(cameraProblem(null)).toBe('The camera could not be opened. Type the barcode instead, or try again.');
    expect(cameraProblem('a string')).toContain('could not be opened');
  });
});
