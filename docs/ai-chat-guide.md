# AI chat help

## Connect

- Open **Settings → AI connection**, add a provider, and save your API key.
- Choose models and a default effort, then **Enable AI**. AI turns off when the app process restarts.
- Use an API account with the provider. ChatGPT Plus and Claude Pro subscriptions do not supply API credit.
- Listed providers include DeepSeek, OpenAI, Anthropic, Google, Moonshot, and API gateways. Presets are integrations, not a guarantee that every account or model works.
- For custom providers, enter an HTTPS base URL and select the API protocol. Only enable capabilities and effort levels that the model supports. Your endpoint receives your key and shared content.
- Saving a key or enabling AI sends nothing. **Test connections** sends a short prompt to each saved connection; **Fetch models** contacts that provider without sharing your workflow or chat. Both require explicit action.

## Chat and changes

- Open AI chat from the Editor. Ask for an explanation, a new workflow, or changes in ordinary language.
- Use the model and effort controls below the message. Vision and thinking depend on the chosen model.
- Replies and available thinking appear as they stream. **Stop** cancels; an active reply can continue on other pages or outside Veyarc.
- Tap a reply's actions to copy it, branch into a new chat, or resend. Resending is another paid request.
- Chats get automatic titles; **Rename chat** lets you choose your own. History supports search, pinning, and deletion.
- Open the changes preview, review the differences, and **Apply to draft**. Save a revision separately. AI never saves or runs a workflow on its own.
- AI may misunderstand your request. Check coordinates, timing, images, and allowed apps before saving or running. If the draft has changed since a proposal, request an updated proposal.

## Photos and files

- Use **+** for the camera, photos, or files. Supported files include images, PDFs, DOCX, Markdown, and text such as TXT, CSV, JSON, and source files.
- Tap an image to crop, rotate, or annotate it before sending. Tap a document for a preview; PDF pages support swiping.
- Images and PDFs need a vision-capable model. PDF pages are sent as images; DOCX is converted to text. AI can propose adding attached images as workflow templates or backgrounds.
- Files must be under 32 MB; extracted text is limited to 1 MB. Large images may be resized, so use original screenshots and check template dimensions when accurate matching matters.

## Privacy and costs

- Requests go directly over HTTPS to the provider address you configured. There is no Veyarc server.
- Shared content includes your message, recent completed exchanges, current workflow text, attached files/photos, and Veyarc's schema and instructions. **Workflow context → Show shared data** shows the workflow text.
- Veyarc does not automatically send live screen captures, recording journals, run history, other workflows, or installed-app lists. Workflow labels, app packages, coordinates, and variables are part of the shared workflow text.
- Your key authenticates requests; it is not part of the model prompt. Keys, chats, and attachments are encrypted locally and excluded from Android backup and workflow exports.
- Your provider sets pricing, retention, and training policies. Local deletion does not delete provider records. Keep secrets out of messages, files, and workflow text.
- Veyarc has no spending cap. Failed, stopped, and resent requests may incur charges. Check your provider balance and policy before use.

## Troubleshooting

- **Key or access error:** check the connection's key, account access, model, and base URL.
- **Balance or rate limit:** check the provider's quota, credit, or usage limits.
- **Unsupported response:** the provider did not return a complete reply in the required format. Retry explicitly or use another model; Veyarc does not silently resend paid requests.
- **Interrupted:** Android stopped the app/service or you cancelled. Your prompt is kept; restart does not resend it.
- **Too large:** send fewer files or less text. Local byte limits protect memory; older exchanges may fall outside the request budget while remaining in local history.
- **Storage error:** retry local storage before sending again. **Chat storage** can delete this workflow's chats or all chats; unsaved editor changes are separate.
