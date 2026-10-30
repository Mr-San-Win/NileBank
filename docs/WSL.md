# WSL workspace

The active working copy is **/home/san_win/NileBank** in the Ubuntu distribution, directly under the san_win home directory.

Repository: [Mr-San-Win/NileBank](https://github.com/Mr-San-Win/NileBank). Development branch: codex/nilebank-mvp. Main must not be pushed or merged automatically.

Open this folder from Ubuntu with your editor, or select the WSL folder in Codex. The earlier Windows folder and /home/san_win/projects/vaultbank are retained as backups; continue development only in /home/san_win/NileBank.

## Run in Ubuntu

Java 21 and Docker are available. Standard commands:

~~~bash
cd /home/san_win/NileBank
sh mvnw -B verify
docker compose up --build
~~~

Open http://localhost:8080. The ignored .env file was carried forward with restrictive permissions. It retains COMPOSE_PROJECT_NAME=nilebank and the existing database name/user so the Docker named volume and simulated funds remain available. Fresh clones use the NileBank defaults in .env.example.

New account/reference identifiers use NB. Existing VB account numbers are accepted as legacy recipients. Historical Flyway migrations must retain their checksums; V6 updates the application metadata.

The first Ubuntu wrapper download was interrupted. Native verification uses Ubuntu Java 21 and a project-local Maven 3.9.11 distribution with the already-downloaded dependency cache. This does not change source code, tests or runtime database behavior. The ordinary wrapper may need internet access on its first run.

GitHub SSH authentication has been verified as Mr-San-Win. A separate gh login is needed only for future CLI API operations such as creating a repository; pushing this existing repository uses SSH.
