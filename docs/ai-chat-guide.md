# AI chat help

Updated 2026-09-29. This help describes the debug preview. Release builds hide AI chat and have no internet permission. Recording, editing, and replay still work offline. Veyarc is not an official DeepSeek app.

## Connect your account

Open Settings, expand AI connection, and choose Add API key. Use your own DeepSeek API key. Keys for Claude, OpenAI, or other providers do not work here. Veyarc currently uses deepseek-flash with a fixed DeepSeek endpoint; there is no model or server selector.

Save key stores it encrypted on this device. Then choose Enable AI, read the disclosure, and confirm Enable for this session. Neither saving nor enabling sends a request. Restarting the app, disabling AI, or replacing/removing the key requires enabling AI again.

Test connection is optional. Send test request sends a short fixed prompt without your workflow or chat history and may incur a charge. Success proves the connection worked, not that a generated workflow is correct. Provider privacy policy, Provider API terms, and Provider pricing in Settings open the provider's website.

## Explain, create, or edit

Open a workflow in the Editor and choose Ask AI. From an empty editor, choose Create with AI. If no chat is selected, choose New chat. You can ask for an explanation without applying anything.

For creation, fill in the allowed app, orientation, and required actions with their coordinates or timing in the editor. An empty or invalid draft may receive questions instead of a proposal. Chat cannot inspect your screen, select an installed app for you, or obtain missing coordinates from an image. This preview has no image or file attachments.

To edit, ask for a specific change, such as changing a wait or workflow name. Use Chat options, then Workflow context, to inspect the current draft text. Send captures the draft again, including unsaved edits. View captured context under a sent message shows the snapshot used for that request.

When a proposal arrives, choose View proposal validation and read the before/after changes. Apply to draft changes only the editable draft. Undo in the editor reverses that Apply. Save revision creates a saved revision; Run is a separate action. Receiving a reply never saves or runs a workflow, changes permissions, or starts recording.

AI can be wrong even when validation passes. Validation checks the workflow format and references, not whether its coordinates match your device or its actions do what you want. If you edit the draft, change workflows, or restore an old chat, a proposal may become stale. Send a new request rather than trying to apply it again. After an interrupted Apply, check the editor before continuing; unsaved editor work may not survive an app restart.

## Requests and costs

DeepSeek bills your own API account. Veyarc has no subscription, account-wide spending cap, or exact per-message cost display. A rejected, interrupted, or stopped reply may still cost money. Check your provider balance and current prices.

Only one request runs at a time. Replies appear after the complete response passes local checks; text does not appear token by token. Use Stop response to cancel. Leaving chat, backgrounding the app, disabling AI, or replacing/removing the key also cancels active work. Rotation keeps the current request without sending another. Restart restores unfinished turns as Interrupted and does not resend them.

Retry request is a new paid attempt using the original prompt and captured workflow snapshot. To use later edits, send a new message. Veyarc does not automatically retry, resume, repair an invalid reply, or switch providers.

## What leaves your device

Send goes directly over HTTPS to api.deepseek.com. There is no Veyarc server. It includes your message, completed valid DeepSeek prompt/reply text from this chat, and the captured draft text: its identity and revision, name, blocks, packages, coordinates, viewport, limits, variables, and image reference IDs/names. Requests also include local conversation/request identifiers, the workflow schema, and Veyarc's instructions. Your key authenticates the HTTPS request; it is not included in the model prompt. DeepSeek receives network information such as your IP address.

Veyarc does not attach screenshots, image bytes, recordings, live screen content, installed-app lists, run history, other workflows, earlier candidate objects, or old workflow snapshots. Local sample replies, failed replies, and incomplete replies are excluded from conversation history sent to DeepSeek. Text you type or paste is sent as written, so do not put credentials or sensitive information in messages, labels, or variables.

No API-specific zero-retention or no-training guarantee has been established for this preview. DeepSeek's general privacy policy is not a substitute for Veyarc's description of this downstream app. Read the provider policy and API terms in Settings before enabling AI. Public network release review is still pending.

## History, keys, and deletion

Chat text, captured snapshots, proposals, titles, and unsent messages are stored encrypted locally using Android Keystore. Conversation/workflow IDs, timestamps, status, and encrypted-file metadata remain in the app-private database. Composer changes save after a short pause. Your saved API key uses separate encrypted storage and is never shown again. Unsaved key entry is discarded when the form closes or the app backgrounds.

Use Chat history to select chats for the current workflow. Chat options provides Rename chat and Delete chat. Chat options, then Chat storage, provides Clear all chat history for all workflows, including unsent messages. These actions do not delete saved workflows or the API key. Deleting a saved workflow also deletes its linked chats.

Remove API key in Settings removes the local key and disables AI. It does not delete chats, revoke the key at DeepSeek, or erase provider records. Revoke a leaked key through your DeepSeek account. Local chat deletion does not request deletion at the provider and is not a promise of forensic erasure.

Keys and chats are excluded from workflow exports and Android backup/device transfer. Uninstalling removes local data and Keystore keys. A .veyarc export contains a workflow revision and its assets, not chat history or a full app backup. Copy reply places the reply on the system clipboard, where it is outside encrypted chat storage.

## When something fails

AI disabled: enable it in Settings for this session. Saving a key alone is not enough. Access denied or balance errors: check your DeepSeek account and API key. Rate limits or service errors: wait and retry only when you choose. Timeout or incomplete response: no proposal from that response can be applied; a retry can incur another charge.

History supports 20 chats, 40 turns per chat, and 4,000 characters per message. Each chat is limited to 256 KiB and all encrypted chat files to 4 MiB. Captured workflow text is limited to 32 KiB, with at most 200 blocks and 12 nesting levels. The total outgoing request is limited to 128 KiB. Oversized content is rejected, not silently shortened. Start a new chat to reduce history, or shorten the message/draft. Delete chats you no longer need when history is full.

Not saved: open Chat storage and read the error. Retry saving writes the visible chat again when available; it does not send an AI request. Reload saved history discards changes that did not reach storage and never resends a message. Preserve any unsaved text you need before reloading or closing the app. Veyarc will not dispatch a chat request if saving its outgoing turn fails.

## Preview limits

Four real-provider smoke requests passed for connection, a simple explanation, clarification, and a name-only proposal with Apply/Undo. Advanced workflow examples passed local fixture tests, which do not prove DeepSeek's quality on those examples. A live reply incorrectly suggested screenshot input and misdescribed the default draft name. Follow the app's controls and validation, not claims made in a reply. Physical-device chat acceptance and human TalkBack listening remain unverified.
