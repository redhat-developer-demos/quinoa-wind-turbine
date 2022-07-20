package org.acme;

public class UserScore implements Comparable<UserScore> {
    
    public String user;
    public Long score;

    public UserScore(String user, Long score) {
        this.user = user;
        this.score = score;
    }

    @Override
    public int compareTo(UserScore o) {
        return this.score.compareTo(o.score);
    }

}
