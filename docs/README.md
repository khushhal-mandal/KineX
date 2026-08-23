# docs/

Images referenced by the root `README.md`. Two are expected and neither exists yet — the
README links to both, so they show as broken images until they land.

| File | What it should show |
| --- | --- |
| `hud.png` | The HUD mid-set: skeleton overlay, progress ring with the rep count inside it, the debug line (`last rep: peak 0.81 · DEPTH`), and a violation chip if one is showing. A squat, since it is the only exercise with validated form rules. Portrait, cropped to the phone screen |
| `kubectl-get-pods.png` | `kubectl get pods -n kinex` with postgres, kinex-api and ollama Running and the ollama-pull Job Completed. Terminal screenshot, dark background, wide enough not to wrap |

Keep them under ~500 KB each; GitHub scales them to the content width, so anything past about
1600 px wide is wasted bytes.
