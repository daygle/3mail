# Security Policy

3mail stores email credentials and handles encryption (TLS, OpenPGP), so we
take security reports seriously. If you have found a vulnerability, please
report it privately — **do not open a public issue**.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting (Security Advisories) so the
report stays confidential until it is fixed:

1. Go to the [Security tab](https://github.com/daygle/3mail/security) of this
   repository.
2. Click **Report a vulnerability** and fill in the form.

Please include:

- The 3mail version or commit you tested.
- The affected area (credential storage, IMAP/SMTP TLS, OpenPGP, remote
  content / HTML rendering, etc.).
- Steps to reproduce, or a proof of concept.
- Your assessment of the impact, if known.

## Supported versions

Only the latest release (or the current `main` branch, if you build from
source) receives security fixes. Older versions should be upgraded as soon as
possible.

## Response and disclosure

- Reports are acknowledged within 3 business days.
- We will work with you to reproduce, fix, and coordinate a release.
- We ask that you allow us 90 days from acknowledgment before any public
  disclosure, so users have time to upgrade.

Thank you for helping keep 3mail and its users safe.
