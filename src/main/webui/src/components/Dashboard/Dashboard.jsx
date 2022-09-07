import React, {useEffect, useState} from 'react';
import {powerApi, gameApi} from '../../api';
import {computeDistance, resetTeam, computePower, computeNbUsers} from './DashboardUtils';
import LeftBar from './LeftBar';
import RaceTrack from './RaceTrack';

const Dashboard = (props) => {
    const [result, setResult] = useState({});
    const [status, setStatus] = useState("initial");
    const [team1, setTeam1] = useState({});
    const [team2, setTeam2] = useState({});
    const [time, setTime] = useState(0);
    const reset = () => {
        setTeam1(resetTeam);
        setTeam2(resetTeam);
        setTime(0);
        setResult({});
    };
    useEffect(() => powerApi.consume(status, [setTeam1, setTeam2]),
        [status]);
    useEffect(() => gameApi.events(setStatus, reset), []);

    const nbUsers = computeNbUsers(team1, team2);
    const power = [computePower(team1), computePower(team2)];
    const distances = [computeDistance(power[0], nbUsers), computeDistance(power[1], nbUsers)];
    useEffect(() => {
        if (!result.team1 && distances[0] >= 100) {
            setResult(p => ({...p, team1: time}))
        } else if (!result.team2 && distances[1] >= 100) {
            setResult(p => ({...p, team2: time}))
        }
        if (result.team1 && result.team2) {
            gameApi.sendEvent('pause');
        }
    }, [time, distances, result])

    return (
        <div className={props.className}>
            <LeftBar team1={team1} team2={team2} power={power} result={result} time={time} setTime={setTime} status={status}/>
            <RaceTrack distances={distances}/>
        </div>
    )
}

export default Dashboard;