# 0GP Race hosted multiplayer API

This is the hosted equivalent of the v1.1b local test server. The RuneLite plugin already talks to the same endpoints, so after deployment you only need to paste the public HTTPS address into **0GP Race -> Multiplayer API URL**.

## Render deployment

1. Create/sign in to a Render account.
2. Create a new **Web Service** from your GitHub repository, or use the included `render.yaml` as a Blueprint.
3. If creating manually, set the Root Directory to `hosted-server`.
4. Start command: `node server.js`.
5. After deployment, Render gives an address similar to `https://0gp-race-api-xxxx.onrender.com`.
6. Open `<your-address>/health`. You should see JSON showing the API is running.
7. In RuneLite, open the 0GP Race settings and change **Multiplayer API URL** to that HTTPS address on every racer PC.

## Important alpha limitation

Rooms are currently held in server memory. A server restart/deploy will clear active rooms. This is fine for hosted-alpha testing. Persistent database-backed rooms are the next backend milestone.
