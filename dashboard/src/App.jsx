import React from 'react';
import Header from './components/Header';
import Hero from './components/Hero';
import ProblemSection from './components/ProblemSection';
import ApproachSection from './components/ApproachSection';
import FeaturesSection from './components/FeaturesSection';
import DownloadCTA from './components/DownloadCTA';

function App() {
  return (
    <>
      <Header />
      <main>
        <Hero />
        <ProblemSection />
        <ApproachSection />
        <FeaturesSection />
        <DownloadCTA />
      </main>
    </>
  );
}

export default App;
