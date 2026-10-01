def commitAuthor(){
    sh 'git show -s --pretty=%an > .git/commitAuthor'
    def commitAuthor = readFile('.git/commitAuthor').trim()
    sh 'rm .git/commitAuthor'
    commitAuthor
}
def commitMessage() {
    sh 'git log --format=%B -n 1 HEAD > .git/commitMessage'
    def commitMessage = readFile('.git/commitMessage').trim()
    sh 'rm .git/commitMessage'
    commitMessage
}
def commitHash(){
    sh 'git show -s --pretty=%h > .git/commitHash'
    def commitHash = readFile('.git/commitHash').trim()
    sh 'rm .git/commitHash'
    commitHash
}
def commitAuthorMail(){
    sh 'git show -s --pretty=%ae > .git/commitAuthorMail'
    def commitAuthorMail = readFile('.git/commitAuthorMail').trim()
    sh 'rm .git/commitAuthorMail'
    commitAuthorMail
}
def commitChanged() {
    sh 'git log --stat --format=%d -n 1 HEAD | head -1>> .git/commitChanged && git log --stat --format=%d -n 1 HEAD | tail -1 >> .git/commitChanged' 
    def commitChanged = readFile('.git/commitChanged')
    sh 'rm .git/commitChanged'
    commitChanged
}
