# Family Hub

Family Hub is a private Android app for one family. It covers:

- **Money:** accounts, credit and debit cards, loans and EMIs, a due calendar, and SMS, Gmail and statement import
- **Health:** activity rings, sleep, heart rate, weight and BMI through Health Connect, plus your own records
- **Everyday life:** reminders, shopping, tasks, documents and events
- **An assistant** that works from your own data

Everyone signs in with Google. A family has a Super Admin, Family Admins and Members.

| Document | What is in it |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Download and install the APK, first start, permissions, and developer setup |
| [docs/FEATURES.md](docs/FEATURES.md) | What is built, section by section |
| [docs/TESTING.md](docs/TESTING.md) | Test results and a checklist for your phone |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | Known limits, and the integrations that need Google's or a partner's approval |
| [PLAN-V2.md](PLAN-V2.md) | The v2 plan, decisions and the money rules |
| [firebase/](firebase/) | Firestore security rules, and a backup of the earlier rules |
| [tools/](tools/) | Plain-Kotlin tests for the money engine, the importers and the assistant |

**Get the latest APK:** Actions → [Build APK](https://github.com/jeetsisotia/family-hub/actions/workflows/build-apk.yml) → newest green run → Artifacts → **family-hub-apk**.
