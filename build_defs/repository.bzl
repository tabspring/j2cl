"""Bazel rule for loading external repository deps for J2CL."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def load_j2cl_repo_deps():
    _github_repo(
        name = "io_bazel_rules_closure",
        repo = "bazelbuild/rules_closure",
        tag = "60194f63aa04181e06e1f763156538e4a623a942",
        sha256 = "2bab61d7747353009cf68391f07b099e680f35ec1a81c39ca5a31030216a0474",
    )

    _github_repo(
        name = "bazel_skylib",
        repo = "bazelbuild/bazel-skylib",
        tag = "1.0.2",
        sha256 = "64ad2728ccdd2044216e4cec7815918b7bb3bb28c95b7e9d951f9d4eccb07625",
    )

    rules_kotlin_version = "v1.5.0"  # v1.5.0
    rules_kotlin_sha = "12d22a3d9cbcf00f2e2d8f0683ba87d3823cb8c7f6837568dd7e48846e023307"

    http_archive(
        name = "io_bazel_rules_kotlin",
        urls = ["https://github.com/bazelbuild/rules_kotlin/releases/download/%s/rules_kotlin_release.tgz" % rules_kotlin_version],
        sha256 = rules_kotlin_sha,
    )

    # Add other closure repo deps that need to loaded beforehand.
    # A differnt alternative would be to let rules_closure load them but then
    # we would need to take setup_j2cl_workspace out of rules.bzl and go with a
    # different setup route - which will be a breaking change.
    # This alternative keeps backward compatibility until we figure out how to
    # proceed.

    _github_repo(
        name = "rules_java",
        repo = "bazelbuild/rules_java",
        tag = "981f06c3d2bd10225e85209904090eb7b5fb26bd",
        sha256 = "7979ece89e82546b0dcd1dff7538c34b5a6ebc9148971106f0e3705444f00665",
    )

    _github_repo(
        name = "rules_proto",
        repo = "bazelbuild/rules_proto",
        tag = "97d8af4dc474595af3900dd85cb3a29ad28cc313",
        sha256 = "e4fe70af52135d2ee592a07f916e6e1fc7c94cf8786c15e8c0d0f08b1fe5ea16",
    )

    _github_repo(
        name = "rules_python",
        repo = "bazelbuild/rules_python",
        tag = "4b84ad270387a7c439ebdccfd530e2339601ef27",
        sha256 = "7e6e20edb31da85be4236b51c6f705e7717b9e7bb8d33fe7d713d3cd270257df",
    )

def _github_repo(name, repo, tag, sha256 = None):
    if native.existing_rule(name):
        return

    _, project_name = repo.split("/")
    http_archive(
        name = name,
        strip_prefix = "%s-%s" % (project_name, tag),
        url = "https://github.com/%s/archive/%s.zip" % (repo, tag),
        sha256 = sha256,
    )
