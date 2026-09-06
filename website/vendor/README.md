# Self-hosted animation dependency

GSAP core 3.15.0 is copied unchanged from the official npm distribution.
No install scripts ran. No remote CDN, trackers, ScrollSmoother, Flip or SplitText are loaded.

- Package: https://registry.npmjs.org/gsap/-/gsap-3.15.0.tgz
- npm integrity: `sha512-dMW4CWBTUK1AEEDeZc1g4xpPGIrSf9fJF960qbTZmN/QwZIWY5wgliS6JWl9/25fpTGJrMRtSjGtOmPnfjZB+A==`
- `gsap-3.15.0.min.js` SHA-256: `92bb9a96476f983d212a2bc4f54c889039c1696dd4461d40a736860938570fbb`
- Copyright and license notice are preserved at the start of the distributed file.
- License: https://gsap.com/standard-license/

This static website has no package-manager dependency tree. To obtain the same official
archive in a normal npm environment, run `npm pack --ignore-scripts gsap@3.15.0` and extract
`package/dist/gsap.min.js`. Verify package integrity before replacing the vendored file;
update the pinned digest in `check.mjs` and the versioned script URL intentionally.
