# Bundled photo skills

Installed 2026-10-08. Original upstream files are unmodified. The Android app
loads SKILL.md and appends image-API delivery constraints. It does not run an
agent, external tools, automatic regeneration, or a visual quality gate.

| Skill directory | Repository | Upstream commit | License |
| --- | --- | --- | --- |
| watercolor-travel-card | https://github.com/GZ-L/photo-to-poster-skills | 268ee78460ab5592167082d0501b7834f2e5b676 | MIT |
| impasto-miniature-landscape | https://github.com/GZ-L/photo-to-poster-skills | 268ee78460ab5592167082d0501b7834f2e5b676 | MIT |
| pixel-dissolve-landscape | https://github.com/GZ-L/photo-to-poster-skills | 268ee78460ab5592167082d0501b7834f2e5b676 | MIT |
| observational-pen-wash | https://github.com/GZ-L/photo-to-poster-skills | 268ee78460ab5592167082d0501b7834f2e5b676 | MIT |
| papercraft-travel-diorama | https://github.com/GZ-L/photo-to-poster-skills | 268ee78460ab5592167082d0501b7834f2e5b676 | MIT |
| scenes-gathered-zine-v1-3 | https://github.com/Zeejay0/gathered-scenes-zine-skill | b9edb836c9dd5c5d80995e89ebcaba34b75fd58f | Personal non-commercial license; see LICENSE |
| photo-to-zine-postcard | https://github.com/Whiplashzeb/photo-to-zine-postcard | 0091403bccb219d1be78c5be8552de29a6446f0a | MIT |
| compose-photo-memory-archive | https://github.com/sbj61188-lab/compose-photo-memory-archive | c4e186e2bc2d57fbf486747d5820d31f49d56291 | MIT |

Gathered Scenes attribution: Visual Skill by @Zeejay0. This skill is for
personal non-commercial use only. Written permission is required for paid,
advertising-supported, organizational, or other commercial use.

Integration differences:
- Zine postcard renders one selectable front or back face per API request.
- Memory archive renders only the companion, then proportionally fits it and
  stitches it to an unchanged, proportionally resized source photo. A provider
  aspect-ratio mismatch produces padding, never a stretched or cropped source.
- No upstream chat output, tool invocation, marketing message, or model name
  changes the user's configured provider or triggers another network request.
