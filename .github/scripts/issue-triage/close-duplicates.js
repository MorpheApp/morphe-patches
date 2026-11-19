// Closes issues that were flagged as possible duplicates at least 3 days ago,
// unless someone commented after the flag or the author reacted with a thumbs down.
// In those cases the label is removed so a maintainer can look at the issue.

const {
  LABEL_POSSIBLE_DUPLICATE,
  DUPLICATE_MARKER,
  isBot,
  removeLabelIfPresent,
} = require('./common.js');

const CLOSE_AFTER_DAYS = 3;

module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const live = process.env.TRIAGE_MODE === 'live';
  const cutoff = Date.now() - CLOSE_AFTER_DAYS * 24 * 60 * 60 * 1000;

  const issues = await github.paginate(github.rest.issues.listForRepo, {
    owner,
    repo,
    state: 'open',
    labels: LABEL_POSSIBLE_DUPLICATE,
    per_page: 100,
  });

  for (const issue of issues) {
    if (issue.pull_request) continue;

    const comments = await github.paginate(github.rest.issues.listComments, {
      owner,
      repo,
      issue_number: issue.number,
      per_page: 100,
    });

    const flagComment = comments
      .filter((comment) => isBot(comment.user) && comment.body.startsWith(DUPLICATE_MARKER))
      .pop();
    if (!flagComment) {
      core.info(`#${issue.number} has no duplicate comment, skipping`);
      continue;
    }

    const flaggedAt = Date.parse(flagComment.created_at);
    const laterComments = comments.filter(
      (comment) => Date.parse(comment.created_at) > flaggedAt && !isBot(comment.user)
    );

    const reactions = await github.paginate(github.rest.reactions.listForIssueComment, {
      owner,
      repo,
      comment_id: flagComment.id,
      per_page: 100,
    });
    const authorDisagrees = reactions.some(
      (reaction) => reaction.content === '-1' && reaction.user && reaction.user.id === issue.user.id
    );

    if (laterComments.length > 0 || authorDisagrees) {
      core.info(`#${issue.number} has a reply or a thumbs down, leaving it open for a maintainer`);
      if (live) {
        await removeLabelIfPresent(github, owner, repo, issue.number, LABEL_POSSIBLE_DUPLICATE);
      }
      continue;
    }

    if (flaggedAt > cutoff) {
      core.info(`#${issue.number} was flagged less than ${CLOSE_AFTER_DAYS} days ago`);
      continue;
    }

    const match = flagComment.body.match(/^<!-- morphe-triage:duplicate (\d+)/);
    if (!match) {
      core.warning(`#${issue.number} duplicate comment has no issue number`);
      continue;
    }
    const duplicateOf = Number(match[1]);

    core.info(`Closing #${issue.number} as a duplicate of #${duplicateOf}`);
    if (!live) continue;

    await github.rest.issues.createComment({
      owner,
      repo,
      issue_number: issue.number,
      body: `Closed as a duplicate of #${duplicateOf}. If this is not a duplicate, please comment here and a maintainer will reopen it.`,
    });
    await removeLabelIfPresent(github, owner, repo, issue.number, LABEL_POSSIBLE_DUPLICATE);
    await github.rest.issues.update({
      owner,
      repo,
      issue_number: issue.number,
      state: 'closed',
      state_reason: 'duplicate',
    });
  }
};
