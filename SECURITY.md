# Security Policy

## Supported version

This project is under active development. Security fixes are applied to the current code on the default branch; older commits or releases are not maintained as separate supported versions unless explicitly stated otherwise.

## Reporting a security issue

Please do **not** open a public GitHub issue for a vulnerability that could expose credentials, private account data, or provide unauthorized access.

Instead, contact the maintainer privately using the contact information available on the maintainer's GitHub profile. Include:

- a description of the issue;
- steps to reproduce it;
- the affected component or endpoint;
- the potential impact;
- a suggested fix, if you have one.

Do not include real credentials unless they are specifically requested through an appropriate private channel.

## Guild Wars 2 API keys

Guild Wars 2 API keys provide read access to account data according to the permissions granted to the key. They cannot be used through the API to modify the Guild Wars 2 account, but they should still not be published in issues, pull requests, screenshots, logs, or committed files.

If a key is accidentally exposed publicly, revoke it through the Guild Wars 2 account/API-key management page and create a replacement.

## Other credentials

Database passwords, deployment credentials, GitHub tokens, and similar secrets must never be committed to the repository or included in public bug reports.

## Scope

Examples of reports that belong here include:

- unintended access to another user's/account's stored data;
- credential exposure;
- authentication or authorization bypasses;
- injection vulnerabilities;
- unsafe handling of untrusted input;
- endpoints that permit unintended state changes;
- sensitive information written to public logs or responses.

Ordinary application bugs, incorrect crafting calculations, UI issues, and feature requests should use the normal GitHub issue templates.
