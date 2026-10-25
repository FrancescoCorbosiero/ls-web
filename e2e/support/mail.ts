import { promises as fs } from 'node:fs';
import path from 'node:path';

/** One .eml file written by the app's directory mail sink (DirectoryMailSender). */
export interface Mail {
  file: string;
  to: string;
  subject: string;
  body: string;
}

export interface MailMatch {
  to: string;
  subject: RegExp;
  body?: RegExp;
}

const mailDir = () => process.env.E2E_MAIL_DIR ?? path.resolve(__dirname, '../../.local/mail/e2e');
const seen = new Set<string>();

async function readMails(): Promise<Mail[]> {
  let names: string[];
  try {
    names = await fs.readdir(mailDir());
  } catch {
    return [];
  }
  const mails: Mail[] = [];
  for (const name of names.filter((n) => n.endsWith('.eml')).sort()) {
    const file = path.join(mailDir(), name);
    const raw = await fs.readFile(file, 'utf8');
    const separator = raw.indexOf('\r\n\r\n');
    const head = separator < 0 ? raw : raw.slice(0, separator);
    const body = separator < 0 ? '' : raw.slice(separator + 4);
    const header = (h: string) => head.match(new RegExp(`^${h}: (.*)$`, 'mi'))?.[1]?.trim() ?? '';
    mails.push({ file, to: header('To').toLowerCase(), subject: header('Subject'), body });
  }
  return mails;
}

/** Waits until the sink holds a not-yet-consumed e-mail to `to` whose subject (and body) match. */
export async function waitForMail(match: MailMatch, timeoutMs = 60_000): Promise<Mail> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const mails = await readMails();
    const found = mails.find(
      (m) =>
        !seen.has(m.file) &&
        m.to === match.to.toLowerCase() &&
        match.subject.test(m.subject) &&
        (match.body === undefined || match.body.test(m.body)),
    );
    if (found) {
      seen.add(found.file);
      return found;
    }
    if (Date.now() > deadline) {
      const summary = mails.map((m) => `${path.basename(m.file)} to=${m.to} subject=${m.subject}`).join('\n');
      throw new Error(`no e-mail to ${match.to} matching ${match.subject} within ${timeoutMs} ms; sink holds:\n${summary}`);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
}

/** The first link of the mail under the given path, as an absolute URL. */
export function linkIn(mail: Mail, pathPrefix: string): string {
  const escaped = pathPrefix.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
  const re = new RegExp(`https?://[^\\s"'<>]+${escaped}[^\\s"'<>]*`);
  const found = mail.body.match(re)?.[0];
  if (!found) {
    throw new Error(`no link to ${pathPrefix} in ${mail.file}`);
  }
  return found.replace(/&amp;/g, '&');
}
